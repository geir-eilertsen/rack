package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One location inside a container, and what is in it.
 *
 * <p><strong>The slot does not own photographs.</strong> Items are the physical
 * things; a photograph hangs off the item it shows. The slot used to keep its own
 * list as well, and having two places to look cost more than it bought: a drawer
 * emptied of items still counted as occupied and could not be deleted, an item
 * moved to another drawer left its picture behind, a photograph could be listed
 * by a slot and rendered by nobody, and deciding whether a file was still in use
 * meant consulting both lists.
 *
 * <p>So {@link #frames()} is derived. It answers the same question the stored
 * list did — which pictures show this drawer — by asking the items, which means
 * it cannot disagree with them.
 */
public record Slot(
    SlotId id,
    List<Item> items,
    Instant lastVerified,
    Instant printedAt
) {

    /**
     * Every photograph naming any item here, in item order, source frame first.
     *
     * <p>Derived, not stored — deliberately not a record component, so it is
     * never written to the slot's JSON.
     */
    public List<String> frames() {
        if (items == null) return List.of();
        LinkedHashSet<String> frames = new LinkedHashSet<>();
        for (Item item : items) {
            if (item.sourcePhoto() != null) frames.add(item.sourcePhoto());
            if (item.seenIn() != null) frames.addAll(item.seenIn());
        }
        return List.copyOf(new ArrayList<>(frames));
    }
}
