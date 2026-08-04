package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Drawer;
import family.eilertsen.rack.domain.model.DrawerId;
import family.eilertsen.rack.domain.model.SearchHit;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartIndex {
    Optional<Drawer> get(DrawerId id);

    void save(Drawer drawer);

    Collection<Drawer> all();

    List<SearchHit> searchByKeyword(String query);

    List<SearchHit> searchBySimilarity(float[] queryVector, int topK);
}
