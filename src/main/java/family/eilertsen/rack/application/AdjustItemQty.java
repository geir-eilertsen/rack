package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdjustItemQty {

    private final PartIndex index;

    public AdjustItemQty(PartIndex index) {
        this.index = index;
    }

    public Slot decrement(ContainerId container, SlotId slotId, int itemIndex) {
        return adjust(container, slotId, itemIndex, -1);
    }

    public Slot increment(ContainerId container, SlotId slotId, int itemIndex) {
        return adjust(container, slotId, itemIndex, 1);
    }

    private Slot adjust(ContainerId container, SlotId slotId, int itemIndex, int delta) {
        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException("Slot has no items: " + container.value() + "/" + slotId.value()));

        if (itemIndex < 0 || itemIndex >= existing.items().size()) {
            throw new IndexOutOfBoundsException(
                "Item index " + itemIndex + " out of range (0.." + (existing.items().size() - 1) + ")");
        }

        Item current = existing.items().get(itemIndex);
        int currentQty = current.qtyEstimate() == null ? 1 : current.qtyEstimate();
        int newQty = currentQty + delta;

        List<Item> newItems = new ArrayList<>(existing.items());
        if (newQty <= 0) {
            newItems.remove(itemIndex);
        } else {
            newItems.set(itemIndex, new Item(
                current.description(),
                current.partNumber(),
                current.category(),
                newQty,
                current.confidence(),
                current.tags(),
                current.embedding()
            ));
        }

        Slot updated = new Slot(existing.id(), List.copyOf(newItems), Instant.now(),
            existing.photos(), existing.printedAt());
        index.save(container, updated);
        return updated;
    }
}
