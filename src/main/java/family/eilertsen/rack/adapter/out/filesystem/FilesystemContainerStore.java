package family.eilertsen.rack.adapter.out.filesystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.port.ContainerStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Component
public class FilesystemContainerStore implements ContainerStore {

    private final Path file;
    private final ObjectMapper mapper;

    public FilesystemContainerStore(@Value("${rack.data-dir}") String dir, ObjectMapper mapper) throws IOException {
        Path base = Path.of(dir).toAbsolutePath();
        Files.createDirectories(base);
        this.file = base.resolve("containers.json");
        this.mapper = mapper;
    }

    @Override
    public List<Container> loadAll() {
        if (!Files.exists(file)) return List.of();
        try {
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + file, e);
        }
    }

    @Override
    public synchronized void saveAll(List<Container> containers) {
        try {
            Path tmp = file.resolveSibling("containers.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), containers);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
