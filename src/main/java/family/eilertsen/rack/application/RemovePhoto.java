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
 * Drops one photograph from a drawer: takes it off every item here that names
 * it, then deletes the file if that was the last thing pointing at it.
 *
 * <p>Since items own photographs, removing an item already takes its frames with
 * it. This is for the narrower case of a picture that is simply no good — out of
 * focus, or of the wrong shelf — on an item worth keeping.
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

        if (!existing.frames().contains(filename)) {
            throw new NoSuchElementException("No such photo on this slot: " + filename);
        }

        // An item was read from the frame being dropped. Leaving it pointing at a
        // photograph that no longer exists would render as a hole.
        List<Item> items = new ArrayList<>();
        for (Item item : existing.items() == null ? List.<Item>of() : existing.items()) {
            items.add(forget(item, filename));
        }

        Slot updated = new Slot(existing.id(), List.copyOf(items), Instant.now(),
            existing.printedAt());
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
            item.qtyEstimate(), item.confidence(), item.tags(), item.qa(),
            source, remaining, item.documents());
    }
}
