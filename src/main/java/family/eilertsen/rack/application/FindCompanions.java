package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Claim;
import family.eilertsen.rack.domain.model.Companion;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PairFinder;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a thing belongs with, and where that is.
 *
 * <p>Photograph a charger and the rack should say which device it charges and
 * which drawer that is in; photograph the device and it should say where the
 * charger is. A phone in one box and its charger in another are both filed
 * correctly and both findable, and neither entry says anything about the
 * other — so the rack is right about both and still sends you to two rooms
 * for one thing.
 *
 * <p><strong>The model is shown the rack, not asked to guess words.</strong>
 * The first version bridged the item's name to the rack's vocabulary and
 * keyword-searched the result, and every live try broke a different way:
 * "automotive" matched the paper towels, "5V" every 35V capacitor, and the
 * one term that would have found the Raspberry Pi was dropped for adding no
 * new word. Reading "5.1V 3A USB-C power supply" beside "Raspberry Pi 4" is a
 * matching job, and the whole listing fits in one prompt — the same argument
 * the project checklist makes — so the model gets the subject and every other
 * entry with its reference, and cites the ones this belongs with.
 *
 * <p><strong>Nothing it says is believed until the index agrees.</strong> A
 * citation has to resolve to an item the rack holds, with the name it was
 * cited under; an invented reference is dropped, and so is the subject citing
 * itself. Same rule as the checklist's {@code verify}. What is already in the
 * subject's own slot is reported as together rather than offered as a move.
 *
 * <p>It is a suggestion, never a move. Which half moves is the user's call,
 * because only they know which box is the phone's home. And nothing is
 * stored: a "belongs with" link would be a second place a fact lives.
 */
@Service
public class FindCompanions {

    /** A general-purpose USB-C charger belongs with every USB-C device on the shelf. */
    private static final int MAX = 12;
    private static final int CACHE_SIZE = 200;
    private static final int DESCRIPTION_CHARS = 160;

    private final PartIndex index;
    private final ContainerRegistry registry;
    private final PairFinder finder;

    /**
     * The same items get looked at over and over. What is cached is the
     * citation by name, not by drawer, so a pair whose halves have since moved
     * still resolves to where they are now.
     */
    private final Map<String, List<Cited>> answers = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Cited>> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    public FindCompanions(PartIndex index, ContainerRegistry registry, PairFinder finder) {
        this.index = index;
        this.registry = registry;
        this.finder = finder;
    }

    /**
     * @param container where the subject is filed; null for an item not yet
     *                  filed, which has no slot of its own and no line of its
     *                  own to keep out of the listing
     */
    public Result execute(Item item, ContainerId container, SlotId slot) {
        if (item == null || blank(item.name())) return new Result(null, List.of(), List.of());

        Ref self = container == null || slot == null ? null : indexOf(item, container, slot);
        Listing listing = listing(self);
        if (listing.lines.isEmpty()) return new Result(item.name(), List.of(), List.of());

        return result(item, citedFor(List.of(item), listing).get(0), listing, self, container, slot);
    }

    /**
     * Several items not yet filed, in one call. A filed batch is several items
     * and the listing is most of the prompt, so one call per batch costs about
     * what one per item does and takes a tenth of the time.
     */
    public List<Result> executeAll(List<Item> items) {
        List<Item> named = items.stream().filter(i -> i != null && !blank(i.name())).toList();
        Listing listing = listing(null);
        List<List<Cited>> cited = named.isEmpty() || listing.lines.isEmpty()
            ? List.of() : citedFor(named, listing);
        List<Result> results = new ArrayList<>();
        int n = 0;
        for (Item item : items) {
            if (item == null || blank(item.name()) || cited.isEmpty()) {
                results.add(new Result(item == null ? null : item.name(), List.of(), List.of()));
            } else {
                results.add(result(item, cited.get(n++), listing, null, null, null));
            }
        }
        return results;
    }

    private Result result(Item item, List<Cited> cited, Listing listing, Ref self, ContainerId container, SlotId slot) {
        List<Found> elsewhere = new ArrayList<>();
        List<Found> here = new ArrayList<>();
        for (Cited c : cited) {
            Found found = resolve(c, listing);
            if (found == null) continue;
            if (self != null && found.container().equals(self.container) && found.slot().equals(self.slot)
                && found.index() == self.index) continue;
            boolean sameSlot = container != null && slot != null
                && found.container().equals(container) && found.slot().equals(slot);
            List<Found> into = sameSlot ? here : elsewhere;
            if (into.size() < MAX && into.stream().noneMatch(f -> samePlace(f, found))) into.add(found);
        }
        return new Result(item.name(), List.copyOf(elsewhere), List.copyOf(here));
    }

    /** Citations per item, in the items' order; only the items not already answered cost a call. */
    private List<List<Cited>> citedFor(List<Item> items, Listing listing) {
        List<String> keys = items.stream().map(FindCompanions::cacheKey).toList();
        List<Integer> unanswered = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!answers.containsKey(keys.get(i))) unanswered.add(i);
        }
        if (!unanswered.isEmpty()) {
            List<String> subjects = unanswered.stream().map(i -> line(null, items.get(i))).toList();
            Map<Integer, List<Cited>> fresh = new LinkedHashMap<>();
            for (int i : unanswered) fresh.put(i, new ArrayList<>());
            List<Claim> claims = new ArrayList<>();
            List<Integer> claimedFor = new ArrayList<>();
            List<Cited> claimed = new ArrayList<>();
            for (Companion c : finder.find(subjects, listing.lines)) {
                Entry entry = listing.byRef.get(c.ref());
                if (entry == null || c.subject() < 0 || c.subject() >= unanswered.size()) continue;    // invented
                claims.add(new Claim(subjects.get(c.subject()), line(null, entry.item), c.why()));
                claimedFor.add(unanswered.get(c.subject()));
                claimed.add(new Cited(c.ref(), entry.item.name(), c.why()));
            }
            // Second opinion, pair by pair. Picked out of a listing of a hundred
            // and fifty lines, "small coil spring for Elco switch" was the pair
            // of an ELKO USB outlet; judged on its own beside it, it is not.
            List<Boolean> stands = claims.isEmpty() ? List.of() : finder.confirm(claims);
            for (int k = 0; k < claimed.size(); k++) {
                if (k < stands.size() && stands.get(k)) fresh.get(claimedFor.get(k)).add(claimed.get(k));
            }
            fresh.forEach((i, cited) -> answers.put(keys.get(i), List.copyOf(cited)));
        }
        return keys.stream().map(k -> answers.getOrDefault(k, List.of())).toList();
    }

    private static String cacheKey(Item item) {
        return normalise(item.name()) + "|" + normalise(clip(item.description()));
    }

    /**
     * The reference first, and only while it still names the item it named:
     * a moved item leaves its old reference pointing at whatever slid into
     * that position, or at nothing. Then by name anywhere, which is what a
     * move leaves behind.
     */
    private Found resolve(Cited c, Listing listing) {
        Entry at = listing.byRef.get(c.ref());
        if (at != null && normalise(at.item.name()).equals(normalise(c.name()))) return found(at, c.why());
        for (Entry e : listing.byRef.values()) {
            if (normalise(e.item.name()).equals(normalise(c.name()))) return found(e, c.why());
        }
        return null;
    }

    private static Found found(Entry e, String why) {
        return new Found(e.container, e.slot, e.index, e.item, why, e.lastVerified);
    }

    private static boolean samePlace(Found a, Found b) {
        return a.container().equals(b.container()) && a.slot().equals(b.slot()) && a.index() == b.index();
    }

    /** Every item in the rack but the subject, in the order containers are listed. */
    private Listing listing(Ref self) {
        List<String> lines = new ArrayList<>();
        Map<String, Entry> byRef = new LinkedHashMap<>();
        for (Container container : registry.all()) {
            for (Slot slot : index.all(container.id())) {
                List<Item> items = slot.items() == null ? List.of() : slot.items();
                for (int i = 0; i < items.size(); i++) {
                    if (self != null && self.container.equals(container.id()) && self.slot.equals(slot.id()) && self.index == i) continue;
                    String ref = container.id().value() + "/" + slot.id().value() + "#" + i;
                    Entry entry = new Entry(container.id(), slot.id(), i, items.get(i), slot.lastVerified());
                    byRef.put(ref, entry);
                    lines.add(line(ref, items.get(i)));
                }
            }
        }
        return new Listing(lines, byRef);
    }

    /** One entry as the model sees it: what it is called, what it looks like, what is printed on it. */
    static String line(String ref, Item item) {
        return (ref == null ? "" : ref + " | ")
            + orBlank(item.name())
            + " | " + clip(item.description())
            + " | " + orBlank(item.partNumber())
            + " | " + orBlank(item.category());
    }

    private Ref indexOf(Item item, ContainerId container, SlotId slot) {
        List<Item> items = index.get(container, slot).map(Slot::items).orElse(List.of());
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == item) return new Ref(container, slot, i);
        }
        // Not the same instance — matched by name, the way a citation is.
        for (int i = 0; i < items.size(); i++) {
            if (normalise(items.get(i).name()).equals(normalise(item.name()))) return new Ref(container, slot, i);
        }
        return null;
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= DESCRIPTION_CHARS ? t : t.substring(0, DESCRIPTION_CHARS) + "…";
    }

    private static String normalise(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String orBlank(String s) {
        return s == null ? "" : s;
    }

    private record Ref(ContainerId container, SlotId slot, int index) {}

    private record Entry(ContainerId container, SlotId slot, int index, Item item, Instant lastVerified) {}

    private record Listing(List<String> lines, Map<String, Entry> byRef) {}

    /** A citation as remembered: by the name it was made under, so a move does not strand it. */
    private record Cited(String ref, String name, String why) {}

    /** One thing the subject belongs with, where it is, and why. */
    public record Found(ContainerId container, SlotId slot, int index, Item item, String why, Instant lastVerified) {}

    /**
     * The name asked about, what it belongs with elsewhere ({@code hits}) and
     * what is already beside it ({@code together}).
     */
    public record Result(String query, List<Found> hits, List<Found> together) {}
}
