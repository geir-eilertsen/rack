package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ContainerRegistry {

    private final Map<ContainerId, Container> byId;

    public ContainerRegistry(List<Container> containers) {
        Map<ContainerId, Container> m = new LinkedHashMap<>();
        for (Container c : containers) m.put(c.id(), c);
        this.byId = Map.copyOf(m);
    }

    public Optional<Container> get(ContainerId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Container> all() {
        return byId.values();
    }
}
