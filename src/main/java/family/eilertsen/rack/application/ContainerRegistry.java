package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.port.ContainerStore;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ContainerRegistry {

    private final ContainerStore store;
    private final Map<ContainerId, Container> byId = new LinkedHashMap<>();

    public ContainerRegistry(ContainerStore store) {
        this.store = store;
        List<Container> loaded = store.loadAll();
        if (loaded.isEmpty()) {
            loaded = List.of(defaultRack());
            store.saveAll(loaded);
        }
        for (Container c : loaded) byId.put(c.id(), c);
    }

    public Optional<Container> get(ContainerId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Container> all() {
        return List.copyOf(byId.values());
    }

    public synchronized void add(Container c) {
        if (byId.containsKey(c.id())) {
            throw new IllegalArgumentException("Container already registered: " + c.id().value());
        }
        byId.put(c.id(), c);
        store.saveAll(List.copyOf(byId.values()));
    }

    private static Container defaultRack() {
        return new Container(new ContainerId("rack"), "Parts rack",
            ContainerLayout.grid(5, 12), 1.0f);
    }
}
