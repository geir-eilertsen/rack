package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Drawer;
import family.eilertsen.rack.domain.model.DrawerId;
import family.eilertsen.rack.domain.model.SearchHit;
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
    private final Map<DrawerId, Drawer> drawers = new ConcurrentHashMap<>();

    public JsonFilePartIndex(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        this.mapper = mapper;
        Files.createDirectories(this.dataDir);
    }

    @PostConstruct
    public void load() throws IOException {
        try (Stream<Path> s = Files.list(dataDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(this::loadOne);
        }
    }

    private void loadOne(Path file) {
        try {
            Drawer d = mapper.readValue(file.toFile(), Drawer.class);
            drawers.put(d.id(), d);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + file, e);
        }
    }

    @Override
    public Optional<Drawer> get(DrawerId id) {
        return Optional.ofNullable(drawers.get(id));
    }

    @Override
    public synchronized void save(Drawer drawer) {
        drawers.put(drawer.id(), drawer);
        Path target = dataDir.resolve(drawer.id().value() + ".json");
        Path tmp = dataDir.resolve(drawer.id().value() + ".json.tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), drawer);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Collection<Drawer> all() {
        return List.copyOf(drawers.values());
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
