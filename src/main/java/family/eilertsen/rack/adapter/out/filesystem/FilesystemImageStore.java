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
import java.util.Set;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class FilesystemImageStore implements ImageStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss");

    /** One folder for the whole rack: a frame belongs to what it shows, not to a drawer. */
    private final Path photoDir;

    /**
     * Scaled copies, beside the photographs rather than among them: {@link #all}
     * lists the photo folder, and a thumbnail is not a photograph nothing
     * points at. {@code thumbs/<edge>/<filename>.jpg}, made on first request
     * and kept — the bytes never change under a name, so neither does the
     * thumbnail. Deleted with the photograph, and swept at boot for any
     * photograph that went while the app was not running.
     */
    private final Path thumbDir;

    public FilesystemImageStore(@Value("${rack.data-dir}") String dataDir) throws IOException {
        Path data = Path.of(dataDir).toAbsolutePath();
        this.photoDir = data.resolve("photos");
        this.thumbDir = data.resolve("thumbs");
        Files.createDirectories(this.photoDir);
        Files.createDirectories(this.thumbDir);
        sweepThumbnails();
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
    public byte[] thumbnail(String filename, int maxEdge) {
        Path cached = thumbDir.resolve(String.valueOf(maxEdge)).resolve(resolve(filename).getFileName() + ".jpg");
        try {
            if (Files.exists(cached)) return Files.readAllBytes(cached);
            byte[] original = read(filename);
            byte[] scaled = scale(original, maxEdge);
            if (scaled == null) return original;
            Files.createDirectories(cached.getParent());
            Path tmp = cached.resolveSibling(cached.getFileName() + ".tmp");
            Files.write(tmp, scaled);
            Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return scaled;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Null for anything ImageIO cannot read — HEIC, WebP — so the original is served as it was. */
    static byte[] scale(byte[] original, int maxEdge) throws IOException {
        // No metadata on a thumbnail: it is a picture for a strip, not a record of anything.
        return Jpegs.fit(original, maxEdge, 0.8f, false);
    }

    /** Thumbnails of photographs that are no longer there. */
    private void sweepThumbnails() throws IOException {
        Set<String> photos = Set.copyOf(all());
        try (Stream<Path> sizes = Files.list(thumbDir)) {
            for (Path size : sizes.filter(Files::isDirectory).toList()) {
                try (Stream<Path> thumbs = Files.list(size)) {
                    for (Path thumb : thumbs.filter(Files::isRegularFile).toList()) {
                        String name = thumb.getFileName().toString();
                        String photo = name.endsWith(".jpg") ? name.substring(0, name.length() - 4) : name;
                        if (!photos.contains(photo)) Files.deleteIfExists(thumb);
                    }
                }
            }
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
            try (Stream<Path> sizes = Files.list(thumbDir)) {
                for (Path size : sizes.filter(Files::isDirectory).toList()) {
                    Files.deleteIfExists(size.resolve(filename + ".jpg"));
                }
            }
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
