package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.port.ImageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class FilesystemImageStore implements ImageStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss");

    /** One folder for the whole rack: a frame belongs to what it shows, not to a drawer. */
    private final Path photoDir;

    public FilesystemImageStore(@Value("${rack.data-dir}") String dataDir) throws IOException {
        this.photoDir = Path.of(dataDir).toAbsolutePath().resolve("photos");
        Files.createDirectories(this.photoDir);
    }

    @Override
    public String store(byte[] image, String contentType) {
        try {
            String stamp = LocalDateTime.now().format(STAMP);
            String extension = extensionFor(contentType);
            // A batch arrives inside the same second, so the timestamp alone is
            // not unique — and now that every drawer shares one folder, two
            // drawers photographed at once would collide too. Suffix until the name is free. The
            // separator is '_' rather than '-' because '_' sorts after '.', so
            // "…-12_1.jpg" follows "…-12.jpg" and listing keeps capture order.
            for (int n = 0; n < 1000; n++) {
                String filename = (n == 0 ? stamp : stamp + "_" + n) + extension;
                try {
                    Files.write(photoDir.resolve(filename), image, StandardOpenOption.CREATE_NEW);
                    return filename;
                } catch (FileAlreadyExistsException taken) {
                    // next suffix
                }
            }
            throw new IllegalStateException("Cannot find a free filename for " + stamp + " in " + photoDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(String filename) {
        try {
            return Files.readAllBytes(resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> all() {
        if (!Files.isDirectory(photoDir)) return List.of();
        try (Stream<Path> files = Files.list(photoDir)) {
            return files.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> !name.endsWith(".tmp"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String filename) {
        try {
            Files.deleteIfExists(resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Refuses anything that is not a plain filename. The name reaches here from
     * a URL, and one folder for the whole rack means a {@code ../} would walk
     * out of the data directory rather than merely into the next drawer.
     */
    private Path resolve(String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
            || filename.contains("..")) {
            throw new IllegalArgumentException("Not a photo filename: " + filename);
        }
        return photoDir.resolve(filename);
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
