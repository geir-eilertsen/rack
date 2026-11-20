package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.BrandStore;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Framing;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.ShopPlace;
import family.eilertsen.rack.domain.model.ShoppingEntry;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.ShoppingList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The shopping list: what to buy, where, and a picture of the right one.
 *
 * <p>Adding from an item copies it — name, part number, description and its
 * source photograph — because the trip to the shop is usually caused by the
 * drawer running out, and an entry that pointed at the item would lose its
 * meaning the moment the last one was used. Nothing here touches the item or
 * stamps {@code lastVerified}: wanting more of something is not looking in
 * the drawer.
 */
@Service
public class Shopping {

    private static final Logger log = LoggerFactory.getLogger(Shopping.class);

    private final ShoppingList list;
    private final PartIndex index;
    private final ImageStore images;
    private final Clock clock;

    public Shopping(ShoppingList list, PartIndex index, ImageStore images) {
        this.list = list;
        this.index = index;
        this.images = images;
        this.clock = Clock.systemUTC();
    }

    public record Text(String what, String store, String partNumber, String details, String qty) {}

    public List<ShoppingEntry> all() {
        return list.all();
    }

    /** Typed in, with or without a picture taken for it. */
    public ShoppingEntry add(Text text, byte[] photo, Framing seen) {
        ShoppingEntry entry = new ShoppingEntry(newId(), text.what(), text.store(), text.partNumber(),
            text.details(), text.qty(), null, null, null, Instant.now(clock), null);
        if (photo != null && photo.length > 0) entry = entry.withPhoto(list.keepPhoto(photo, seen));
        list.save(entry);
        return entry;
    }

    /**
     * "Buy more of this." {@code store}, {@code qty} and {@code note} are the
     * person's; the rest is the item's as it reads now. The note goes ahead of
     * the item's description, since it is the thing the person just thought of
     * ("the 6mm ones, not 8").
     */
    public ShoppingEntry addFromItem(ContainerId container, SlotId slotId, int itemIndex,
                                     String store, String qty, String note) {
        return addFromItem(container, slotId, itemIndex, store, qty, note, false);
    }

    /**
     * With {@code rememberBrand}, the store the person named is also written
     * down as where the item's brand is sold — but only when the item has a
     * brand and no rule says where it is sold yet. An existing rule is the
     * owner's earlier decision and a shopping trip does not overrule it; that
     * is done at the foot of the list page.
     */
    public ShoppingEntry addFromItem(ContainerId container, SlotId slotId, int itemIndex,
                                     String store, String qty, String note, boolean rememberBrand) {
        Item item = item(container, slotId, itemIndex);
        if (rememberBrand && store != null && !store.isBlank() && item.brand() != null
                && ruleFor(item.brand()).isEmpty()) {
            setBrand(item.brand(), store);
        }
        if (store == null || store.isBlank()) store = storeFor(item).map(StoreHint::store).orElse(null);
        String what = item.name() != null && !item.name().isBlank() ? item.name() : item.description();
        if (what == null || what.isBlank()) what = "Item from " + container.value() + "/" + slotId.value();
        if (what.length() > 120) what = what.substring(0, 120) + "…";

        String details = join(note, item.name() != null && !item.name().isBlank() ? item.description() : null);
        ShoppingEntry entry = new ShoppingEntry(newId(), what, store, item.partNumber(), details, qty,
            null, container.value(), slotId.value(), Instant.now(clock), null);

        String source = item.sourcePhoto();
        if (source != null) {
            try {
                entry = entry.withPhoto(list.keepPhoto(images.read(source), null));
            } catch (RuntimeException e) {
                // A picture is the point, but the words are still worth having.
                log.warn("Could not copy {} onto the shopping list: {}", source, e.toString());
            }
        }
        list.save(entry);
        return entry;
    }

    private Item item(ContainerId container, SlotId slotId, int itemIndex) {
        Slot slot = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException("No such slot: " + container.value() + "/" + slotId.value()));
        List<Item> items = slot.items() == null ? List.of() : slot.items();
        if (itemIndex < 0 || itemIndex >= items.size()) {
            throw new IndexOutOfBoundsException("No item " + itemIndex + " in " + container.value() + "/" + slotId.value());
        }
        return items.get(itemIndex);
    }

    /**
     * Where to buy another, and the words on the item that say so.
     * {@code unplacedBrand} is the item's recorded brand when no rule says
     * where that brand is sold — what the panel offers to remember once the
     * person names the shop. Null when there is no brand or it is placed.
     */
    public record StoreHint(String store, String because, String unplacedBrand) {
        public StoreHint(String store, String because) {
            this(store, because, null);
        }
    }

    public Optional<StoreHint> hintFor(ContainerId container, SlotId slot, int itemIndex) {
        Item item = item(container, slot, itemIndex);
        String unplaced = item.brand() != null && ruleFor(item.brand()).isEmpty() ? item.brand() : null;
        Optional<StoreHint> hint = storeFor(item);
        if (unplaced == null) return hint;
        return Optional.of(hint.map(h -> new StoreHint(h.store(), h.because(), unplaced))
            .orElse(new StoreHint(null, null, unplaced)));
    }

    public Optional<StoreHint> storeFor(ContainerId container, SlotId slot, int itemIndex) {
        return storeFor(item(container, slot, itemIndex));
    }

    /**
     * Read off the item's own words, never guessed. A brand on the list of house
     * brands names its store ("Anslut" → Jula); failing that, a store already
     * used on the list or in the brand rules, named in the item's text ("Farnell
     * 876-7670"), is its own answer. Whole words only, ignoring case — "Jula" in
     * a description must not be found inside some longer word.
     */
    Optional<StoreHint> storeFor(Item item) {
        // The brand the extractor read off the box, when it recorded one, is
        // the strongest evidence there is: it was read as a brand, where a word
        // in the description might be anything.
        if (item.brand() != null) {
            Optional<BrandStore> rule = ruleFor(item.brand());
            if (rule.isPresent()) {
                return Optional.of(new StoreHint(rule.get().store(),
                    item.brand() + " is sold at " + rule.get().store()));
            }
        }
        String text = String.join(" ", nonNull(item.name()), nonNull(item.description()), nonNull(item.partNumber()),
            nonNull(item.brand()), item.tags() == null ? "" : String.join(" ", item.tags()));
        for (BrandStore rule : list.brands()) {
            if (mentions(text, rule.brand())) {
                return Optional.of(new StoreHint(rule.store(), rule.brand() + " is sold at " + rule.store()));
            }
        }
        Set<String> stores = new LinkedHashSet<>();
        list.brands().forEach(b -> stores.add(b.store()));
        list.all().forEach(e -> { if (e.store() != null) stores.add(e.store()); });
        for (String store : stores) {
            if (mentions(text, store)) return Optional.of(new StoreHint(store, "the item names " + store));
        }
        return Optional.empty();
    }

    /**
     * The rule for a recorded brand: the same brand ignoring case, or a rule's
     * brand standing as a whole word in it — "Anslut" covers a box reading
     * "Anslut Pro".
     */
    private Optional<BrandStore> ruleFor(String brand) {
        List<BrandStore> rules = list.brands();
        for (BrandStore rule : rules) {
            if (rule.brand().equalsIgnoreCase(brand.strip())) return Optional.of(rule);
        }
        for (BrandStore rule : rules) {
            if (mentions(brand, rule.brand())) return Optional.of(rule);
        }
        return Optional.empty();
    }

    private static boolean mentions(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    public List<BrandStore> brands() {
        return list.brands();
    }

    /**
     * Every store the list knows of: on a line, in a brand rule, or as a saved
     * shop spot — grouped ignoring case under the spelling used most, sorted.
     * Offered in every store box, so the next trip to Leroy goes under "Leroy"
     * and not "leroy ". Read from all three because each forgets on its own:
     * clearing what was bought takes its lines, and a rule or a spot may be
     * the only place a store was ever written.
     */
    public List<String> stores() {
        Map<String, Map<String, Integer>> spellings = new HashMap<>();
        list.all().forEach(e -> countSpelling(spellings, e.store()));
        list.brands().forEach(b -> countSpelling(spellings, b.store()));
        list.places().forEach(p -> countSpelling(spellings, p.store()));
        List<String> stores = new ArrayList<>();
        for (Map<String, Integer> forms : spellings.values()) {
            stores.add(forms.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey());
        }
        stores.sort(String.CASE_INSENSITIVE_ORDER);
        return stores;
    }

    private static void countSpelling(Map<String, Map<String, Integer>> spellings, String name) {
        if (name == null || name.isBlank()) return;
        String n = name.strip();
        spellings.computeIfAbsent(n.toLowerCase(Locale.ROOT), k -> new HashMap<>()).merge(n, 1, Integer::sum);
    }

    /** Adds a rule, or replaces the store for a brand already written down. */
    public List<BrandStore> setBrand(String brand, String store) {
        BrandStore rule = new BrandStore(brand, store);
        List<BrandStore> next = new ArrayList<>();
        boolean replaced = false;
        for (BrandStore b : list.brands()) {
            if (b.brand().toLowerCase(Locale.ROOT).equals(rule.brand().toLowerCase(Locale.ROOT))) {
                next.add(rule);
                replaced = true;
            } else {
                next.add(b);
            }
        }
        if (!replaced) next.add(rule);
        list.saveBrands(next);
        return list.brands();
    }

    public List<BrandStore> forgetBrand(String brand) {
        list.saveBrands(list.brands().stream()
            .filter(b -> !b.brand().equalsIgnoreCase(brand == null ? "" : brand.trim())).toList());
        return list.brands();
    }

    /**
     * The saved shop spots. The page works out which one it is at on the
     * phone; {@link ShopArrivals} does the same with what Vaier's broker
     * hears, and keeps none of it.
     */
    public List<ShopPlace> places() {
        return list.places();
    }

    /** "I'm at Jula": the phone's position, saved as one of Jula's spots. */
    public ShopPlace savePlace(String store, double lat, double lon, Double accuracy) {
        ShopPlace place = new ShopPlace(newId(), store, lat, lon, accuracy, Instant.now(clock));
        List<ShopPlace> next = new ArrayList<>(list.places());
        next.add(place);
        list.savePlaces(next);
        return place;
    }

    public List<ShopPlace> forgetPlace(String id) {
        list.savePlaces(list.places().stream().filter(p -> !p.id().equals(id)).toList());
        return list.places();
    }

    public ShoppingEntry edit(String id, Text text) {
        ShoppingEntry e = get(id);
        ShoppingEntry next = e.withText(
            text.what() != null ? text.what() : e.what(),
            text.store() != null ? text.store() : e.store(),
            text.partNumber() != null ? text.partNumber() : e.partNumber(),
            text.details() != null ? text.details() : e.details(),
            text.qty() != null ? text.qty() : e.qty());
        list.save(next);
        return next;
    }

    /** Ticked off in the shop, or unticked when the till said otherwise. */
    public ShoppingEntry setBought(String id, boolean bought) {
        ShoppingEntry e = get(id);
        if (e.bought() == bought) return e;
        ShoppingEntry next = e.withBought(bought ? Instant.now(clock) : null);
        list.save(next);
        return next;
    }

    public void remove(String id) {
        ShoppingEntry e = get(id);
        list.delete(id);
        list.dropPhoto(e.photo());
    }

    /** Clears what has been bought; returns how many went. */
    public int clearBought() {
        List<ShoppingEntry> done = list.all().stream().filter(ShoppingEntry::bought).toList();
        for (ShoppingEntry e : done) remove(e.id());
        return done.size();
    }

    public byte[] photo(String name) {
        return list.photo(name);
    }

    public byte[] thumbnail(String name, int maxEdge) {
        return list.thumbnail(name, maxEdge);
    }

    private ShoppingEntry get(String id) {
        return list.all().stream().filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new NoSuchElementException("Not on the shopping list: " + id));
    }

    private static String join(String a, String b) {
        boolean hasA = a != null && !a.isBlank(), hasB = b != null && !b.isBlank();
        if (hasA && hasB) return a.trim() + "\n" + b.trim();
        return hasA ? a.trim() : hasB ? b.trim() : null;
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
