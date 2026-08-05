package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
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
    public String store(ContainerId container, SlotId slot, byte[] image, String contentType) {
        try {
            Path dir = photoDir(container, slot);
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(STAMP);
            String extension = extensionFor(contentType);
            // A batch of photos of one slot arrives inside the same second, so the
            // timestamp alone is not unique. Suffix until the name is free. The
            // separator is '_' rather than '-' because '_' sorts after '.', so
            // "…-12_1.jpg" follows "…-12.jpg" and listing keeps capture order.
            for (int n = 0; n < 1000; n++) {
                String filename = (n == 0 ? stamp : stamp + "_" + n) + extension;
                try {
                    Files.write(dir.resolve(filename), image, StandardOpenOption.CREATE_NEW);
                    return filename;
                } catch (FileAlreadyExistsException taken) {
                    // next suffix
                }
            }
            throw new IllegalStateException("Cannot find a free filename for " + stamp + " in " + dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(ContainerId container, SlotId slot, String filename) {
        try {
            return Files.readAllBytes(photoDir(container, slot).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> list(ContainerId container, SlotId slot) {
        Path dir = photoDir(container, slot);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(ContainerId container, SlotId slot, String filename) {
        try {
            Files.deleteIfExists(photoDir(container, slot).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path photoDir(ContainerId container, SlotId slot) {
        return dataDir.resolve(container.value()).resolve(slot.value());
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
