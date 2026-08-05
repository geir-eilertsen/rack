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
public class EditItem {

    private final PartIndex index;

    public EditItem(PartIndex index) {
        this.index = index;
    }

    public Slot execute(ContainerId container, SlotId slotId, int itemIndex, Fields update) {
        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException("Slot has no items: " + container.value() + "/" + slotId.value()));

        if (itemIndex < 0 || itemIndex >= existing.items().size()) {
            throw new IndexOutOfBoundsException(
                "Item index " + itemIndex + " out of range (0.." + (existing.items().size() - 1) + ")");
        }

        Item current = existing.items().get(itemIndex);
        Item updated = new Item(
            blankToNull(update.name()),
            blankToNull(update.description()),
            blankToNull(update.partNumber()),
            blankToNull(update.category()),
            update.qtyEstimate(),
            1.0,
            update.tags() == null ? List.of() : List.copyOf(update.tags()),
            current.embedding(),
            current.qa(),
            current.sourcePhoto(),
            current.seenIn()
        );

        List<Item> newItems = new ArrayList<>(existing.items());
        newItems.set(itemIndex, updated);
        Slot saved = new Slot(existing.id(), List.copyOf(newItems), Instant.now(),
            existing.photos(), existing.printedAt());
        index.save(container, saved);
        return saved;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record Fields(
        String name,
        String description,
        String partNumber,
        String category,
        Integer qtyEstimate,
        List<String> tags
    ) {}
}
