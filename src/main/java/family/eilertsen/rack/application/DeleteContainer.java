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
 * Drops a container's registration and its slot state. Refuses while any of its slots still holds items, so the
 * index can never lose track of something that physically exists.
 *
 * <p>The slot state used to be left on disk so re-registering the same id picked it back up. What that actually
 * left was a folder indistinguishable from a live container, and photo references nothing could reach — keeping
 * frames alive for something that no longer existed. Archived label sheets stay: they are the record of what was
 * physically printed, and that outlives a registration.
 */
@Service
public class DeleteContainer {

    private static final int MAX_LISTED = 10;

    private final ContainerRegistry registry;
    private final PartIndex index;
    private final ForgetUnusedPhotos unusedPhotos;

    public DeleteContainer(ContainerRegistry registry, PartIndex index, ForgetUnusedPhotos unusedPhotos) {
        this.registry = registry;
        this.index = index;
        this.unusedPhotos = unusedPhotos;
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
        // Its slot state goes too. Leaving it behind meant a folder that looks
        // like a live container, and photo references nothing could reach still
        // keeping frames alive for something that no longer exists.
        index.forget(id);
        unusedPhotos.sweep();
        return c;
    }

    /**
     * Items are the claim that something physically exists in there, and hiding that claim is what this refuses
     * to do. A photograph is not such a claim.
     *
     * <p>It used to be, on the grounds that dropping the registration would orphan a file that still meant
     * something. That stopped being true when photographs moved to one folder for the whole rack: the file is
     * not under the container, {@code data/<container>/} is left on disk regardless, and re-registering the same
     * id picks the slot state, and the items' photographs with it, back up. So nothing is orphaned and nothing is lost — while a
     * drawer emptied of items but still listing one frame could not be deleted at all, with nothing on screen to
     * remove and no way to remove it.
     *
     * <p>A printed label is not content either: an empty slot that happens to have been labelled stays deletable.
     */
    private static boolean holdsContent(Slot s) {
        return s.items() != null && !s.items().isEmpty();
    }

    private static String describe(Container c, List<String> occupied) {
        String listed = String.join(", ", occupied.subList(0, Math.min(MAX_LISTED, occupied.size())));
        String more = occupied.size() > MAX_LISTED ? ", and " + (occupied.size() - MAX_LISTED) + " more" : "";
        String slots = occupied.size() == 1 ? "slot" : "slots";
        return "\"" + c.name() + "\" still holds items in " + occupied.size() + " " + slots
            + " (" + listed + more + "). Move or remove them first.";
    }
}
