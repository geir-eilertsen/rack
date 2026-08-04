package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Container;

import java.util.List;

public interface ContainerStore {
    List<Container> loadAll();

    void saveAll(List<Container> containers);
}
