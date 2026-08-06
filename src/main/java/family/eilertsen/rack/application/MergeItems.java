package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Document;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Folds one row of a slot into another: the same thing, recorded twice.
 *
 * <p>Filing appends, so topping a drawer up leaves the thing in two rows, and
 * taking some out later decrements whichever row you happened to tap. Nothing
 * here decides <em>whether</em> two rows are the same thing — a person does,
 * because two bags of black heat shrink may genuinely be two bags and no
 * comparison of the words can tell.
 *
 * <p>The photographs are added together as well as the quantities. Each row was
 * seen in its own frames and the merged row was seen in all of them; dropping
 * the other row's frames would throw away the evidence for the stock it brought.
 */
@Service
public class MergeItems {

    private final PartIndex index;

    public MergeItems(PartIndex index) {
        this.index = index;
    }

    /**
     * Keeps {@code keepIndex}'s identity — its name, description, part number,
     * tags and Q&A — and takes the other's quantity and frames.
     */
    public Slot execute(ContainerId container, SlotId slotId, int keepIndex, int dropIndex) {
        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException(
                "Slot has no items: " + container.value() + "/" + slotId.value()));

        List<Item> items = existing.items() == null ? List.of() : existing.items();
        require(keepIndex, items.size());
        require(dropIndex, items.size());
        if (keepIndex == dropIndex) {
            throw new IllegalArgumentException("An item cannot be merged into itself");
        }

        Item keep = items.get(keepIndex);
        Item drop = items.get(dropIndex);

        Item merged = new Item(
            keep.name(), keep.description(), keep.partNumber(), keep.category(),
            qty(keep) + qty(drop),
            keep.confidence(), keep.tags(), keep.qa(),
            keep.sourcePhoto() != null ? keep.sourcePhoto() : drop.sourcePhoto(),
            framesOf(keep, drop), documentsOf(keep, drop));

        List<Item> updated = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (i == dropIndex) continue;
            updated.add(i == keepIndex ? merged : items.get(i));
        }

        Slot saved = new Slot(existing.id(), List.copyOf(updated), Instant.now(),
            existing.printedAt());
        index.save(container, saved);
        return saved;
    }

    /**
     * Every frame either row was seen in, the kept row's first. Null when
     * neither knew — an item catalogued before frames were recorded still falls
     * back to the slot's own strip, and inventing one here would say more than
     * is known.
     */
    /**
     * Both rows' datasheets, the survivor's first — the same treatment their
     * photographs get, and for the same reason: two entries for one part may have
     * been documented from different sides, and a merge should lose neither.
     */
    private static List<Document> documentsOf(Item keep, Item drop) {
        Map<String, Document> byFile = new LinkedHashMap<>();
        for (Item item : List.of(keep, drop)) {
            for (Document d : item.documents()) byFile.putIfAbsent(d.filename(), d);
        }
        return List.copyOf(byFile.values());
    }

    private static List<String> framesOf(Item keep, Item drop) {
        Set<String> frames = new LinkedHashSet<>();
        addFrames(frames, keep);
        addFrames(frames, drop);
        return frames.isEmpty() ? null : List.copyOf(frames);
    }

    /** Each row contributes as a whole, so the kept row's frames stay in front. */
    private static void addFrames(Set<String> frames, Item item) {
        if (item.seenIn() != null && !item.seenIn().isEmpty()) {
            frames.addAll(item.seenIn());
        } else if (item.sourcePhoto() != null) {
            // A row from before seenIn existed still knows the one frame it was
            // read from, and that frame is as much a picture of the merged row.
            frames.add(item.sourcePhoto());
        }
    }

    /** A quantity nobody estimated is one of the thing, not none of it. */
    private static int qty(Item item) {
        return item.qtyEstimate() == null ? 1 : item.qtyEstimate();
    }

    private static void require(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Item index " + index + " out of range (0.." + (size - 1) + ")");
        }
    }
}
