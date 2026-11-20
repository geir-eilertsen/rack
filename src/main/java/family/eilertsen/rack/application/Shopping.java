package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.BrandStore;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Framing;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Shop;
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
     * down as one that sells the item's brand, when the item has a brand and
     * the store is not among those already written down for it. Added beside
     * them, never in place of them: Komplett selling Logitech does not stop
     * Amazon selling it.
     */
    public ShoppingEntry addFromItem(ContainerId container, SlotId slotId, int itemIndex,
                                     String store, String qty, String note, boolean rememberBrand) {
        Item item = item(container, slotId, itemIndex);
        if (rememberBrand && store != null && !store.isBlank() && item.brand() != null
                && !sells(item.brand(), store)) {
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
     *
     * <p>{@code store} is the first suggestion and {@code stores} every store
     * the brand rules name for it — a brand is usually sold in more than one
     * place, and which is nearer, cheaper or quicker today is the person's
     * call, so the panel offers them all. {@code brand} is the item's recorded
     * brand, so the panel can offer to remember a store not yet among them.
     */
    public record StoreHint(String store, String because, String brand, List<String> stores) {
        public StoreHint {
            stores = stores == null ? List.of() : List.copyOf(stores);
        }

        public StoreHint(String store, String because) {
            this(store, because, null, store == null ? List.of() : List.of(store));
        }
    }

    public Optional<StoreHint> hintFor(ContainerId container, SlotId slot, int itemIndex) {
        Item item = item(container, slot, itemIndex);
        Optional<StoreHint> hint = storeFor(item);
        if (item.brand() == null) return hint;
        return Optional.of(hint.map(h -> new StoreHint(h.store(), h.because(), item.brand(), h.stores()))
            .orElse(new StoreHint(null, null, item.brand(), List.of())));
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
            List<String> stores = storesOf(rulesFor(item.brand()));
            if (!stores.isEmpty()) return Optional.of(soldAt(item.brand(), stores));
        }
        String text = String.join(" ", nonNull(item.name()), nonNull(item.description()), nonNull(item.partNumber()),
            nonNull(item.brand()), item.tags() == null ? "" : String.join(" ", item.tags()));
        for (BrandStore rule : list.brands()) {
            if (mentions(text, rule.brand())) {
                return Optional.of(soldAt(rule.brand(), storesOf(rulesFor(rule.brand()))));
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

    private boolean sells(String brand, String store) {
        return storesOf(rulesFor(brand)).stream().anyMatch(st -> st.equalsIgnoreCase(store.strip()));
    }

    private static StoreHint soldAt(String brand, List<String> stores) {
        return new StoreHint(stores.get(0), brand + " is sold at " + and(stores), brand, stores);
    }

    private static String and(List<String> words) {
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    /**
     * The rules for a recorded brand: the same brand ignoring case, or failing
     * that, a rule's brand standing as a whole word in it — "Anslut" covers a
     * box reading "Anslut Pro". In the order they were written down.
     */
    private List<BrandStore> rulesFor(String brand) {
        List<BrandStore> rules = list.brands();
        List<BrandStore> same = rules.stream().filter(r -> r.brand().equalsIgnoreCase(brand.strip())).toList();
        if (!same.isEmpty()) return same;
        for (BrandStore rule : rules) {
            if (mentions(brand, rule.brand())) {
                return rules.stream().filter(r -> r.brand().equalsIgnoreCase(rule.brand())).toList();
            }
        }
        return List.of();
    }

    /** Distinct ignoring case, first spelling kept. */
    private static List<String> storesOf(List<BrandStore> rules) {
        Map<String, String> seen = new java.util.LinkedHashMap<>();
        rules.forEach(r -> seen.putIfAbsent(r.store().toLowerCase(Locale.ROOT), r.store()));
        return List.copyOf(seen.values());
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
        list.shops().forEach(s -> countSpelling(spellings, s.name()));
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

    /**
     * Writes down that {@code store} sells {@code brand}, beside any other
     * stores already written down for it. Saying it twice, in any spelling,
     * changes nothing; the brand keeps the spelling it already has.
     */
    public List<BrandStore> setBrand(String brand, String store) {
        BrandStore rule = new BrandStore(brand, store);
        List<BrandStore> current = list.brands();
        boolean known = current.stream().anyMatch(b ->
            b.brand().equalsIgnoreCase(rule.brand()) && b.store().equalsIgnoreCase(rule.store()));
        if (known) return current;
        String spelled = current.stream().map(BrandStore::brand).filter(b -> b.equalsIgnoreCase(rule.brand()))
            .findFirst().orElse(rule.brand());
        List<BrandStore> next = new ArrayList<>(current);
        next.add(new BrandStore(spelled, rule.store()));
        list.saveBrands(next);
        return list.brands();
    }

    /** One store for a brand, or with {@code store} null every store for it. */
    public List<BrandStore> forgetBrand(String brand, String store) {
        String b = brand == null ? "" : brand.trim();
        list.saveBrands(list.brands().stream()
            .filter(r -> !(r.brand().equalsIgnoreCase(b) && (store == null || r.store().equalsIgnoreCase(store.trim()))))
            .toList());
        return list.brands();
    }

    public List<BrandStore> forgetBrand(String brand) {
        return forgetBrand(brand, null);
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
        shop(store).filter(Shop::online).ifPresent(s -> {
            throw new IllegalArgumentException(s.name() + " is an online shop — there is nowhere to stand in it");
        });
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

    public List<Shop> shops() {
        return list.shops();
    }

    /** What is said about {@code store}, matched ignoring case; empty for a store nobody has described. */
    public Optional<Shop> shop(String store) {
        if (store == null || store.isBlank()) return Optional.empty();
        return list.shops().stream().filter(s -> s.named(store)).findFirst();
    }

    /**
     * Says whether a store is online and how to search it, replacing what was
     * said before under any spelling. Keeps the spelling already in use when
     * there is one, so "amazon" typed in a hurry does not rename "Amazon".
     */
    public List<Shop> setShop(String name, boolean online, String searchUrl) {
        Shop given = new Shop(name, online, searchUrl);
        List<Shop> next = new ArrayList<>();
        boolean replaced = false;
        for (Shop s : list.shops()) {
            if (s.named(given.name())) {
                next.add(new Shop(s.name(), given.online(), given.searchUrl()));
                replaced = true;
            } else {
                next.add(s);
            }
        }
        if (!replaced) next.add(given);
        next.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        list.saveShops(next);
        return list.shops();
    }

    public List<Shop> forgetShop(String name) {
        list.saveShops(list.shops().stream().filter(s -> !s.named(name == null ? "" : name)).toList());
        return list.shops();
    }

    /**
     * Ordered, with whatever identifies the order — or, with {@code ordered}
     * false, not ordered after all. Arriving is {@link #setBought}: an ordered
     * line that arrives is in hand exactly as a ticked-off one is.
     */
    public ShoppingEntry setOrdered(String id, boolean ordered, String ref) {
        ShoppingEntry e = get(id);
        ShoppingEntry next = ordered
            ? e.withOrdered(e.orderedAt() != null ? e.orderedAt() : Instant.now(clock), ref != null ? ref : e.orderRef())
            : e.withOrdered(null, null);
        list.save(next);
        return next;
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
