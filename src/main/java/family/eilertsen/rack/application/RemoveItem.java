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
public class RemoveItem {

    private final PartIndex index;

    public RemoveItem(PartIndex index) {
        this.index = index;
    }

    public Slot execute(ContainerId container, SlotId slotId, int itemIndex) {
        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException("Slot has no items: " + container.value() + "/" + slotId.value()));

        if (itemIndex < 0 || itemIndex >= existing.items().size()) {
            throw new IndexOutOfBoundsException(
                "Item index " + itemIndex + " out of range (0.." + (existing.items().size() - 1) + ")");
        }

        List<Item> newItems = new ArrayList<>(existing.items());
        newItems.remove(itemIndex);

        Slot updated = new Slot(existing.id(), List.copyOf(newItems), Instant.now(),
            existing.photos(), existing.printedAt());
        index.save(container, updated);
        return updated;
    }
}
