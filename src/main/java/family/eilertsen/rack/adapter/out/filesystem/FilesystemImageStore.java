package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.model.DrawerId;
import family.eilertsen.rack.domain.port.ImageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

@Component
public class FilesystemImageStore implements ImageStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss");

    private final Path dataDir;

    public FilesystemImageStore(@Value("${rack.data-dir}") String dataDir) throws IOException {
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        Files.createDirectories(this.dataDir);
    }

    @Override
    public String store(DrawerId drawer, byte[] image, String contentType) {
        try {
            Path dir = dataDir.resolve(drawer.value());
            Files.createDirectories(dir);
            String filename = LocalDateTime.now().format(STAMP) + extensionFor(contentType);
            Files.write(dir.resolve(filename), image, StandardOpenOption.CREATE_NEW);
            return filename;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(DrawerId drawer, String filename) {
        try {
            return Files.readAllBytes(dataDir.resolve(drawer.value()).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> list(DrawerId drawer) {
        Path dir = dataDir.resolve(drawer.value());
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) return ".jpg";
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic", "image/heif" -> ".heic";
            default -> ".jpg";
        };
    }
}
