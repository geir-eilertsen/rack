package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Drops one photograph from a drawer.
 *
 * <p>Removing an item deliberately leaves its frames behind: an unreferenced
 * frame is the evidence that the extraction missed something, so it outlives
 * what was read from it. That was the whole story until a drawer emptied of
 * items still counted as occupied and could not be deleted, with nothing on
 * screen to remove and no way to remove it. Keeping a photograph has to be a
 * decision, which means there has to be a way to decide otherwise.
 */
@Service
public class RemovePhoto {

    private final PartIndex index;
    private final ImageStore images;

    public RemovePhoto(PartIndex index, ImageStore images) {
        this.index = index;
        this.images = images;
    }

    public Slot execute(ContainerId container, SlotId slotId, String filename) {
        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException(
                "Slot has no state: " + container.value() + "/" + slotId.value()));

        List<String> photos = existing.photos() == null ? List.of() : existing.photos();
        if (!photos.contains(filename)) {
            throw new NoSuchElementException("No such photo on this slot: " + filename);
        }

        List<String> kept = new ArrayList<>(photos);
        kept.remove(filename);

        // An item may have been read from the frame being dropped. Leaving it
        // pointing at a photograph that no longer exists would render as a hole.
        List<Item> items = new ArrayList<>();
        for (Item item : existing.items() == null ? List.<Item>of() : existing.items()) {
            items.add(forget(item, filename));
        }

        Slot updated = new Slot(existing.id(), List.copyOf(items), Instant.now(),
            List.copyOf(kept), existing.printedAt());
        index.save(container, updated);

        // Photographs live in one folder for the whole rack, so the file only
        // goes once nothing anywhere still points at it — a frame can show three
        // drawers' worth of things, and one of them being finished with it says
        // nothing about the others.
        Set<String> stillInUse = index.photosInUse();
        if (!stillInUse.contains(filename)) images.delete(filename);

        return updated;
    }

    private static Item forget(Item item, String filename) {
        boolean wasSource = filename.equals(item.sourcePhoto());
        List<String> seenIn = item.seenIn();
        boolean wasSeenIn = seenIn != null && seenIn.contains(filename);
        if (!wasSource && !wasSeenIn) return item;

        List<String> frames = seenIn == null ? null : new ArrayList<>(seenIn);
        if (frames != null) frames.remove(filename);
        List<String> remaining = frames == null || frames.isEmpty() ? null : List.copyOf(frames);
        String source = wasSource ? (remaining == null ? null : remaining.get(0)) : item.sourcePhoto();

        return new Item(item.name(), item.description(), item.partNumber(), item.category(),
            item.qtyEstimate(), item.confidence(), item.tags(), item.embedding(), item.qa(),
            source, remaining);
    }
}
