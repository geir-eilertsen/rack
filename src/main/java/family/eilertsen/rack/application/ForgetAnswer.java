package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Drops one exchange from an item's Q&amp;A.
 *
 * <p>An answer is model prose kept beside the item for good, and searched — so
 * a confidently wrong one is worse than none: it is read as fact the next time
 * the row is opened, and it can put the item under a query it has no business
 * matching. The person who asked is the only one who can tell it was wrong, and
 * this is how they say so.
 *
 * <p>Does not stamp {@code lastVerified}. Deleting a sentence at a desk is not
 * looking in the drawer.
 */
@Service
public class ForgetAnswer {

    private final PartIndex index;

    public ForgetAnswer(PartIndex index) {
        this.index = index;
    }

    public Slot execute(ContainerId container, SlotId slotId, int itemIndex, int exchange) {
        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException("Slot has no items: " + container.value() + "/" + slotId.value()));

        if (itemIndex < 0 || itemIndex >= existing.items().size()) {
            throw new IndexOutOfBoundsException(
                "Item index " + itemIndex + " out of range (0.." + (existing.items().size() - 1) + ")");
        }

        Item current = existing.items().get(itemIndex);
        List<Item.QA> qa = current.qa() == null ? List.of() : current.qa();
        if (exchange < 0 || exchange >= qa.size()) {
            throw new IndexOutOfBoundsException(
                "Question " + exchange + " out of range (0.." + (qa.size() - 1) + ")");
        }

        List<Item.QA> kept = new ArrayList<>(qa);
        kept.remove(exchange);

        Item updated = new Item(
            current.name(),
            current.description(),
            current.partNumber(),
            current.category(),
            current.qtyEstimate(),
            current.confidence(),
            current.tags(),
            List.copyOf(kept),
            current.sourcePhoto(),
            current.seenIn(),
            current.documents()
        );

        List<Item> items = new ArrayList<>(existing.items());
        items.set(itemIndex, updated);
        Slot saved = new Slot(existing.id(), List.copyOf(items), existing.lastVerified(), existing.printedAt());
        index.save(container, saved);
        return saved;
    }
}
