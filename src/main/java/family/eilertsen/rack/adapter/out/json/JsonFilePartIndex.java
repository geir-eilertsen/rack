package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class JsonFilePartIndex implements PartIndex {

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final Map<ContainerId, Map<SlotId, Slot>> byContainer = new ConcurrentHashMap<>();

    public JsonFilePartIndex(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        this.mapper = mapper;
        Files.createDirectories(this.dataDir);
    }

    @PostConstruct
    public void load() throws IOException {
        if (!Files.isDirectory(dataDir)) return;
        try (Stream<Path> containers = Files.list(dataDir)) {
            containers.filter(Files::isDirectory).forEach(this::loadContainer);
        }
    }

    private void loadContainer(Path containerDir) {
        ContainerId cid;
        try {
            cid = new ContainerId(containerDir.getFileName().toString());
        } catch (IllegalArgumentException e) {
            return;
        }
        Map<SlotId, Slot> slots = new ConcurrentHashMap<>();
        try (Stream<Path> files = Files.list(containerDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        Slot slot = mapper.readValue(p.toFile(), Slot.class);
                        slots.put(slot.id(), slot);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to load " + p, e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed listing " + containerDir, e);
        }
        byContainer.put(cid, slots);
    }

    @Override
    public Optional<Slot> get(ContainerId container, SlotId slot) {
        Map<SlotId, Slot> slots = byContainer.get(container);
        return slots == null ? Optional.empty() : Optional.ofNullable(slots.get(slot));
    }

    @Override
    public synchronized void save(ContainerId container, Slot slot) {
        byContainer.computeIfAbsent(container, k -> new ConcurrentHashMap<>()).put(slot.id(), slot);
        Path containerDir = dataDir.resolve(container.value());
        try {
            Files.createDirectories(containerDir);
            Path target = containerDir.resolve(slot.id().value() + ".json");
            Path tmp = containerDir.resolve(slot.id().value() + ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), slot);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Collection<Slot> all(ContainerId container) {
        Map<SlotId, Slot> slots = byContainer.get(container);
        return slots == null ? List.of() : List.copyOf(slots.values());
    }

    @Override
    public List<SearchHit> searchByKeyword(String query) {
        return List.of();
    }

    @Override
    public List<SearchHit> searchBySimilarity(float[] queryVector, int topK) {
        return List.of();
    }
}
