package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Document;
import family.eilertsen.rack.domain.model.FccListing;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.FccRegistry;
import family.eilertsen.rack.domain.port.PartIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts an <em>FCC</em> link on every item whose own text carries an FCC ID
 * the register has a page for.
 *
 * <p>The extractor writes an FCC ID into the description ("FCC ID
 * DZLMRCJ134") rather than calling it a part number, and the register's page
 * for it is the one public document every radio device has: photographs
 * inside and out, the user manual, the test reports. It is checked rather than
 * assumed — a misread digit is a 404 — and only a page that is there becomes
 * a link.
 *
 * <p>Runs right after the item's text is written — filing, editing, a resync
 * — and once at boot for what was filed before. The boot pass asks only about
 * IDs never asked about, so a link the owner removed stays removed; an item
 * newly written with a known ID is linked from the kept answer without asking
 * again. Adding a link does not stamp {@code lastVerified}: reading a register
 * is not looking in the drawer.
 */
@Service
public class FccLinks {

    private static final Logger log = LoggerFactory.getLogger(FccLinks.class);

    /**
     * "FCC ID: DZLMRCJ134", "FCC-ID DZLMRCJ134", "fcc id 2AB3C-X1". The words
     * in any case; the ID itself upper-case with a digit in it, as every
     * grant is — so "FCC ID label on the underside" is not a grant called
     * "label".
     */
    static final Pattern FCC_ID = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(?i:FCC[\\s-]*ID)(?![\\p{L}])[\\s:#.]*"
            + "(?=[A-Z0-9-]*[0-9])([A-Z0-9][A-Z0-9-]{2,18}[A-Z0-9])(?![\\p{L}\\p{N}-])");

    /** After the register could not be reached, leave it alone this long. */
    static final Duration QUIET = Duration.ofMinutes(10);

    private final PartIndex index;
    private final ContainerRegistry registry;
    private final FccRegistry fcc;
    private final boolean enabled;
    private final Clock clock;
    private Instant quietUntil = Instant.MIN;

    @Autowired
    public FccLinks(PartIndex index, ContainerRegistry registry, FccRegistry fcc,
                    @Value("${rack.fcc.lookup:true}") boolean enabled) {
        this(index, registry, fcc, enabled, Clock.systemUTC());
    }

    FccLinks(PartIndex index, ContainerRegistry registry, FccRegistry fcc, boolean enabled, Clock clock) {
        this.index = index;
        this.registry = registry;
        this.fcc = fcc;
        this.enabled = enabled;
        this.clock = clock;
    }

    /** The FCC ID an item's own words carry, upper-cased, if any. */
    static Optional<String> fccIdOf(Item item) {
        List<String> fields = new ArrayList<>();
        fields.add(item.partNumber());
        fields.add(item.name());
        fields.add(item.description());
        if (item.tags() != null) fields.addAll(item.tags());
        for (String field : fields) {
            if (field == null) continue;
            Matcher m = FCC_ID.matcher(field);
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    static boolean hasLink(Item item, String id) {
        return item.documents().stream().anyMatch(d -> d.isLink()
            && d.url().toLowerCase(Locale.ROOT).contains("fccid.io/" + id.toLowerCase(Locale.ROOT)));
    }

    /**
     * Links what in this slot can be linked, after its items were just
     * written. The slot as it now stands when something was added, empty when
     * nothing changed — so a caller returns the fresh one.
     */
    public Optional<Slot> link(ContainerId container, SlotId slotId) {
        return link(container, slotId, false);
    }

    /**
     * {@code onlyUnasked}: the boot pass, which leaves alone every ID already
     * asked about — what is linked is linked, and what is not was either not
     * listed or taken off by hand.
     */
    synchronized Optional<Slot> link(ContainerId container, SlotId slotId, boolean onlyUnasked) {
        if (!enabled) return Optional.empty();
        Optional<Slot> before = index.get(container, slotId);
        if (before.isEmpty() || before.get().items() == null) return Optional.empty();

        // Ask first, without holding anything; the slot is read again to write.
        Map<String, FccListing> found = new HashMap<>();
        for (Item item : before.get().items()) {
            Optional<String> id = fccIdOf(item);
            if (id.isEmpty() || hasLink(item, id.get()) || found.containsKey(id.get())) continue;
            if (onlyUnasked && fcc.asked(id.get())) continue;
            if (!fcc.asked(id.get()) && clock.instant().isBefore(quietUntil)) continue;
            try {
                fcc.lookup(id.get()).ifPresent(l -> found.put(id.get(), l));
            } catch (RuntimeException e) {
                log.warn("Could not check FCC ID {}: {}", id.get(), e.toString());
                quietUntil = clock.instant().plus(QUIET);
            }
        }
        if (found.isEmpty()) return Optional.empty();

        Slot slot = index.get(container, slotId).orElse(before.get());
        List<Item> items = new ArrayList<>();
        boolean changed = false;
        for (Item item : slot.items()) {
            Optional<String> id = fccIdOf(item);
            FccListing listing = id.map(found::get).orElse(null);
            if (listing == null || hasLink(item, id.get())) {
                items.add(item);
                continue;
            }
            List<Document> docs = new ArrayList<>(item.documents());
            docs.add(new Document(null, listing.title(), null, 0, clock.instant(), listing.url()));
            items.add(new Item(item.name(), item.description(), item.partNumber(), item.brand(), item.category(),
                item.qtyEstimate(), item.confidence(), item.tags(), item.qa(), item.sourcePhoto(), item.seenIn(),
                List.copyOf(docs)));
            changed = true;
        }
        if (!changed) return Optional.empty();
        // lastVerified carried across untouched — see the class note.
        Slot saved = new Slot(slot.id(), List.copyOf(items), slot.lastVerified(), slot.printedAt());
        index.save(container, saved);
        return Optional.of(saved);
    }

    /** Every slot, asking only about IDs never asked about. */
    public void sweep() {
        int linked = 0;
        for (Container container : registry.all()) {
            for (Slot slot : index.all(container.id())) {
                if (link(container.id(), slot.id(), true).isPresent()) linked++;
            }
        }
        if (linked > 0) log.info("Linked FCC registrations in {} slot(s)", linked);
    }

    /** Off the request path: a slow register must not hold up the boot. */
    @EventListener(ApplicationReadyEvent.class)
    public void atBoot() {
        if (!enabled) return;
        Thread t = new Thread(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                log.warn("FCC sweep stopped: {}", e.toString());
            }
        }, "rack-fcc");
        t.setDaemon(true);
        t.start();
    }
}
