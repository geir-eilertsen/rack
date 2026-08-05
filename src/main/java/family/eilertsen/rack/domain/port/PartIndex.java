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

    List<SearchHit> searchByKeyword(String query);

    List<SearchHit> searchBySimilarity(float[] queryVector, int topK);

    /**
     * Every distinct short label in the index — item names, categories and tags.
     * The words this rack actually uses, for grounding a {@link QueryExpander}.
     */
    Set<String> vocabulary();
}
