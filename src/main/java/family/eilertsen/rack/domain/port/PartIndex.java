package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PartIndex {
    Optional<Slot> get(ContainerId container, SlotId slot);

    void save(ContainerId container, Slot slot);

    Collection<Slot> all(ContainerId container);

    /**
     * Drops everything the index knows about a container.
     *
     * <p>Deleting a container used to leave its slot state on disk so that
     * re-registering the same id picked it back up. That left a folder that
     * looks like a live container and, worse, kept its photo references alive —
     * so frames belonging to something that no longer exists stayed protected
     * from the sweep by state nothing could reach.
     */
    void forget(ContainerId container);

    List<SearchHit> searchByKeyword(String query);

    /**
     * Every distinct short label in the index — item names, categories and tags.
     * The words this rack actually uses, for grounding a {@link QueryExpander}.
     */
    Set<String> vocabulary();

    /**
     * Every photo filename anything still points at, across every container.
     *
     * <p>Photographs are kept in one folder for the whole rack, so a frame one
     * drawer is finished with may be the only picture another drawer's item
     * has. Deleting is only safe against this answer, not against one slot's
     * own list.
     */
    Set<String> photosInUse();
}
