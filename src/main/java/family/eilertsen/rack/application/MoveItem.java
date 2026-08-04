package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
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
public class MoveItem {

    private final PartIndex index;
    private final ContainerRegistry registry;

    public MoveItem(PartIndex index, ContainerRegistry registry) {
        this.index = index;
        this.registry = registry;
    }

    public Slot execute(ContainerId srcContainer, SlotId srcSlot, int itemIndex,
                         ContainerId dstContainer, SlotId dstSlot) {
        Container dst = registry.get(dstContainer)
            .orElseThrow(() -> new NoSuchElementException("Unknown destination container: " + dstContainer.value()));
        if (!dst.slots().contains(dstSlot)) {
            throw new IllegalArgumentException("Destination slot not in container: " + dstContainer.value() + "/" + dstSlot.value());
        }
        if (srcContainer.equals(dstContainer) && srcSlot.equals(dstSlot)) {
            throw new IllegalArgumentException("Source and destination are the same slot");
        }

        Slot src = index.get(srcContainer, srcSlot)
            .orElseThrow(() -> new NoSuchElementException("Source slot has no state: " + srcContainer.value() + "/" + srcSlot.value()));
        if (itemIndex < 0 || itemIndex >= src.items().size()) {
            throw new IndexOutOfBoundsException(
                "Item index " + itemIndex + " out of range (0.." + (src.items().size() - 1) + ")");
        }

        Item original = src.items().get(itemIndex);
        // Drop source_photo: the photo file lives in the source slot's directory
        // and may have captured other items. User can re-photograph in the new
        // location if they want a visual.
        Item moved = new Item(
            original.description(),
            original.partNumber(),
            original.category(),
            original.qtyEstimate(),
            original.confidence(),
            original.tags(),
            original.embedding(),
            original.qa(),
            null
        );

        Instant now = Instant.now();

        List<Item> srcItems = new ArrayList<>(src.items());
        srcItems.remove(itemIndex);
        Slot newSrc = new Slot(src.id(), List.copyOf(srcItems), now, src.photos(), src.printedAt());
        index.save(srcContainer, newSrc);

        Slot dstSlotState = index.get(dstContainer, dstSlot)
            .orElse(new Slot(dstSlot, List.of(), null, List.of(), null));
        List<Item> dstItems = new ArrayList<>(dstSlotState.items());
        dstItems.add(moved);
        Slot newDst = new Slot(dstSlotState.id(), List.copyOf(dstItems), now, dstSlotState.photos(), dstSlotState.printedAt());
        index.save(dstContainer, newDst);

        return newDst;
    }
}
