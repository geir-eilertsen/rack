package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.port.DocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * {@code data/documents/}, flat.
 *
 * <p>The stored name keeps the one you uploaded, cleaned up: a service manual
 * called "Quad 405-2 606 707 service manual.pdf" is worth recognising in a
 * directory listing six months later, where a timestamp is not. Everything
 * outside {@code [A-Za-z0-9._-]} is folded to a hyphen, and a collision gets a
 * numeric suffix rather than overwriting — two revisions of one manual are two
 * documents.
 */
@Component
public class FilesystemDocumentStore implements DocumentStore {

    private static final int MAX_NAME = 96;

    private final Path dir;

    public FilesystemDocumentStore(@Value("${rack.data-dir}") String dataDir) throws IOException {
        // normalize() matters, and its absence rejected every document in the
        // container. rack.data-dir is "./data", so toAbsolutePath() keeps the "."
        // segment — "/app/./data/documents" — while the resolved-and-normalised
        // path below loses it. startsWith then failed for every legal filename,
        // not merely for a traversal. The unit test missed it because @TempDir
        // hands out a path with no "." in it.
        this.dir = Path.of(dataDir).toAbsolutePath().normalize().resolve("documents");
        Files.createDirectories(this.dir);
    }

    @Override
    public String store(byte[] bytes, String originalFilename, String contentType) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("the document is empty");
        String base = safeBase(originalFilename);
        String ext = extension(originalFilename, contentType);
        try {
            Files.createDirectories(dir);
            String name = base + ext;
            for (int n = 2; Files.exists(dir.resolve(name)); n++) {
                name = base + "_" + n + ext;
            }
            Files.write(dir.resolve(name), bytes);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(String filename) {
        try {
            return Files.readAllBytes(resolve(filename));
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("No such document: " + filename);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> all() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile).sorted()
                .forEach(p -> names.add(p.getFileName().toString()));
            return List.copyOf(names);
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
     * One folder for the whole rack means a name carrying a slash or a "…" would
     * walk out of the data directory rather than merely into the next project.
     */
    private Path resolve(String filename) {
        if (filename == null || filename.isBlank()
            || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Not a document name: " + filename);
        }
        Path p = dir.resolve(filename).normalize();
        if (!p.startsWith(dir)) throw new IllegalArgumentException("Not a document name: " + filename);
        return p;
    }

    private static String safeBase(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (name.isEmpty()) name = "document";
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

    private static String extension(String original, String contentType) {
        String name = original == null ? "" : original.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot).replaceAll("[^a-z0-9.]", "");
            if (ext.length() >= 2 && ext.length() <= 6) return ext;
        }
        // No usable extension on the upload: fall back to the type the browser
        // declared, so the file at least opens as what it is.
        if (contentType == null) return "";
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "text/plain" -> ".txt";
            default -> "";
        };
    }
}
