package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Drops a container's registration. Refuses while any of its slots still holds items, so the index can never
 * lose track of something that physically exists. Files under {@code data/<container>/} are left on disk —
 * re-registering the same id picks the slot state back up.
 */
@Service
public class DeleteContainer {

    private static final int MAX_LISTED = 10;

    private final ContainerRegistry registry;
    private final PartIndex index;

    public DeleteContainer(ContainerRegistry registry, PartIndex index) {
        this.registry = registry;
        this.index = index;
    }

    public Container execute(ContainerId id) {
        Container c = registry.get(id)
            .orElseThrow(() -> new NoSuchElementException("Unknown container: " + id.value()));

        // Every slot the index knows about, not just the ones in the current layout, so strays can't slip through.
        // Reported in layout order ("1, 2, 11", not "1, 11, 2"); anything off-layout sorts to the end.
        List<SlotId> layout = c.slots();
        List<String> occupied = index.all(id).stream()
            .filter(DeleteContainer::holdsContent)
            .map(Slot::id)
            .sorted(Comparator
                .comparingInt((SlotId sid) -> {
                    int i = layout.indexOf(sid);
                    return i < 0 ? layout.size() : i;
                })
                .thenComparing(SlotId::value))
            .map(SlotId::value)
            .toList();

        if (!occupied.isEmpty()) {
            throw new IllegalStateException(describe(c, occupied));
        }

        registry.remove(id);
        return c;
    }

    /**
     * A photo counts as content even when nothing was extracted from it — the photo is the ground truth and the
     * items are only an index over it, so dropping the registration would orphan a file that still means something.
     * A printed label is not content: an empty slot that happens to have been labelled stays deletable.
     */
    private static boolean holdsContent(Slot s) {
        boolean hasItems = s.items() != null && !s.items().isEmpty();
        boolean hasPhotos = s.photos() != null && !s.photos().isEmpty();
        return hasItems || hasPhotos;
    }

    private static String describe(Container c, List<String> occupied) {
        String listed = String.join(", ", occupied.subList(0, Math.min(MAX_LISTED, occupied.size())));
        String more = occupied.size() > MAX_LISTED ? ", and " + (occupied.size() - MAX_LISTED) + " more" : "";
        String slots = occupied.size() == 1 ? "slot" : "slots";
        return "\"" + c.name() + "\" still holds items or photos in " + occupied.size() + " " + slots
            + " (" + listed + more + "). Move or remove them first.";
    }
}
