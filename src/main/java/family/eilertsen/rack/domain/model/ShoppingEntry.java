package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * One thing to buy, carrying enough to buy the <em>right</em> one: a picture,
 * a part number, and whatever distinguishes it from the thing beside it on the
 * store shelf. Standing in an aisle of forty kinds of wall plug, "plugs" is not
 * a shopping list entry; the photo of the ones in the drawer is.
 *
 * <p>An entry made from an item is a <strong>copy</strong> of what the item said
 * at that moment, not a reference to it. The item may be used up, renamed,
 * moved or deleted before the trip happens — running out is usually why it is
 * on the list — and the entry must still say what to buy. {@code container}
 * and {@code slot} say where the original lived, as a courtesy link; nothing
 * depends on it still being there.
 *
 * <p>{@code photo} is a file the list owns, kept beside the list rather than
 * among the rack's photographs: the rack deletes a photograph no item names,
 * and an entry must not lose its picture because the last one was taken out
 * of the drawer.
 *
 * <p>{@code store} is free text ("Leroy", "Biltema"), grouped on ignoring case.
 * Null means anywhere.
 *
 * <p>{@code orderedAt} is for a line bought where nothing is in your hands at
 * the till: ordered online, it is on its way, and the line stays on the list
 * as <em>ordered</em> until it arrives — which is {@code boughtAt}, the same
 * moment a shop line is ticked off. {@code orderRef} is whatever identifies
 * the order, typed by the person: an order number or a tracking link.
 */
public record ShoppingEntry(
    String id,
    String what,
    String store,
    String partNumber,
    String details,
    String qty,
    String photo,
    String container,
    String slot,
    Instant addedAt,
    Instant boughtAt,
    Instant orderedAt,
    String orderRef
) {
    public static final int MAX_TEXT = 2000;

    public ShoppingEntry {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,40}")) throw new IllegalArgumentException("Bad shopping entry id: " + id);
        what = clean(what);
        if (what == null) throw new IllegalArgumentException("Say what to buy");
        store = clean(store);
        partNumber = clean(partNumber);
        details = clean(details);
        qty = clean(qty);
        container = clean(container);
        slot = clean(slot);
        orderRef = clean(orderRef);
    }

    /** A line written before anything could be ordered. */
    public ShoppingEntry(String id, String what, String store, String partNumber, String details, String qty,
                         String photo, String container, String slot, Instant addedAt, Instant boughtAt) {
        this(id, what, store, partNumber, details, qty, photo, container, slot, addedAt, boughtAt, null, null);
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.length() > MAX_TEXT) throw new IllegalArgumentException("Too long: " + t.substring(0, 40) + "…");
        return t;
    }

    public boolean bought() {
        return boughtAt != null;
    }

    /** Ordered and not yet here: off the list of things to go and get, still on the list. */
    public boolean ordered() {
        return orderedAt != null && boughtAt == null;
    }

    /** Still to be got: neither in hand nor on its way. */
    public boolean open() {
        return boughtAt == null && orderedAt == null;
    }

    public ShoppingEntry withBought(Instant at) {
        return new ShoppingEntry(id, what, store, partNumber, details, qty, photo, container, slot, addedAt, at,
            orderedAt, orderRef);
    }

    public ShoppingEntry withOrdered(Instant at, String ref) {
        return new ShoppingEntry(id, what, store, partNumber, details, qty, photo, container, slot, addedAt, boughtAt,
            at, at == null ? null : ref);
    }

    public ShoppingEntry withPhoto(String name) {
        return new ShoppingEntry(id, what, store, partNumber, details, qty, name, container, slot, addedAt, boughtAt,
            orderedAt, orderRef);
    }

    public ShoppingEntry withText(String what, String store, String partNumber, String details, String qty) {
        return new ShoppingEntry(id, what, store, partNumber, details, qty, photo, container, slot, addedAt, boughtAt,
            orderedAt, orderRef);
    }
}
