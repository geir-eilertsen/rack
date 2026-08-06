package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.port.ProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code data/projects/<id>.json}, written the way slot state is: to a
 * {@code .tmp} and moved atomically, single writer on a single box.
 */
@Component
public class JsonFileProjectStore implements ProjectStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileProjectStore.class);

    private final Path dir;
    private final ObjectMapper mapper;

    public JsonFileProjectStore(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        this.dir = Path.of(dataDir).toAbsolutePath().resolve("projects");
        this.mapper = mapper;
        Files.createDirectories(this.dir);
    }

    @Override
    public List<Project> loadAll() {
        if (!Files.isDirectory(dir)) return List.of();
        List<Project> projects = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    projects.add(mapper.readValue(p.toFile(), Project.class));
                } catch (IOException | IllegalArgumentException e) {
                    // One unreadable project must not take the other projects — or
                    // the app — with it. The file stays put to be looked at.
                    log.warn("Ignoring unreadable project {}: {}", p.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed listing " + dir, e);
        }
        return List.copyOf(projects);
    }

    @Override
    public synchronized void save(Project project) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(project.id().value() + ".json");
            Path tmp = dir.resolve(project.id().value() + ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), project);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void delete(ProjectId id) {
        try {
            Files.deleteIfExists(dir.resolve(id.value() + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
