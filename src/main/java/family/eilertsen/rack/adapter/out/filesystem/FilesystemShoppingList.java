package family.eilertsen.rack.adapter.out.filesystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.BrandStore;
import family.eilertsen.rack.domain.model.Framing;
import family.eilertsen.rack.domain.model.ShopPlace;
import family.eilertsen.rack.domain.model.ShoppingEntry;
import family.eilertsen.rack.domain.port.ShoppingList;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code data/shopping.json}, {@code data/shopping-brands.json} and {@code data/shopping-photos/}. One file
 * for the list, because it is a handful of lines edited one at a time; written
 * to a {@code .tmp} and moved atomically, the way slot state is.
 *
 * <p>Pictures nothing on the list names are swept at boot, so a crash between
 * keeping one and saving the entry leaves nothing behind for long.
 */
@Component
public class FilesystemShoppingList implements ShoppingList {

    private static final Logger log = LoggerFactory.getLogger(FilesystemShoppingList.class);

    /** Long enough to read a label on a bag in the aisle; the rack's own edge. */
    static final int PHOTO_EDGE = 1568;

    private final Path file;
    private final Path brandsFile;
    private final Path placesFile;
    private final Path photoDir;
    private final ObjectMapper mapper;
    private final List<ShoppingEntry> entries = new ArrayList<>();
    private final List<BrandStore> brands = new ArrayList<>();
    private final List<ShopPlace> places = new ArrayList<>();

    public FilesystemShoppingList(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        // Beside label-runs.json and searches.json rather than in a folder of
        // its own: every directory under data/ is read as a container, and its
        // *.json as slot files — data/shopping/list.json took the app down.
        Path dir = Path.of(dataDir).toAbsolutePath().normalize();
        this.file = dir.resolve("shopping.json");
        this.brandsFile = dir.resolve("shopping-brands.json");
        this.placesFile = dir.resolve("shopping-places.json");
        this.photoDir = dir.resolve("shopping-photos");
        this.mapper = mapper;
        Files.createDirectories(photoDir);
        load();
        sweep();
    }

    private void load() {
        if (Files.exists(file)) try {
            entries.addAll(mapper.readValue(file.toFile(), new TypeReference<List<ShoppingEntry>>() {}));
        } catch (IOException | IllegalArgumentException e) {
            // Left on disk to be looked at; an unreadable list must not stop the rack.
            log.warn("Ignoring unreadable shopping list {}: {}", file, e.toString());
        }
        if (Files.exists(brandsFile)) try {
            brands.addAll(mapper.readValue(brandsFile.toFile(), new TypeReference<List<BrandStore>>() {}));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Ignoring unreadable brand list {}: {}", brandsFile, e.toString());
        }
        if (Files.exists(placesFile)) try {
            places.addAll(mapper.readValue(placesFile.toFile(), new TypeReference<List<ShopPlace>>() {}));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Ignoring unreadable shop places {}: {}", placesFile, e.toString());
        }
    }

    private void sweep() throws IOException {
        Set<String> named = new HashSet<>();
        for (ShoppingEntry e : entries) if (e.photo() != null) named.add(e.photo());
        try (Stream<Path> files = Files.list(photoDir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                if (!named.contains(p.getFileName().toString())) Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public synchronized List<ShoppingEntry> all() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized void save(ShoppingEntry entry) {
        List<ShoppingEntry> next = new ArrayList<>(entries);
        int at = indexOf(next, entry.id());
        if (at >= 0) next.set(at, entry); else next.add(entry);
        write(next);
    }

    @Override
    public synchronized void delete(String id) {
        List<ShoppingEntry> next = new ArrayList<>(entries);
        int at = indexOf(next, id);
        if (at < 0) return;
        next.remove(at);
        write(next);
    }

    private static int indexOf(List<ShoppingEntry> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        return -1;
    }

    private void write(List<ShoppingEntry> next) {
        try {
            Path tmp = file.resolveSibling("shopping.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            entries.clear();
            entries.addAll(next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String keepPhoto(byte[] image, Framing seen) {
        try {
            byte[] fitted = Jpegs.fit(image, PHOTO_EDGE, 0.85f, false, seen);
            String name = UUID.randomUUID().toString().substring(0, 12) + ".jpg";
            Files.write(photoDir.resolve(name), fitted != null ? fitted : image);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] photo(String name) {
        try {
            return Files.readAllBytes(resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Made per request rather than cached: a list is a dozen lines, not a rack. */
    @Override
    public byte[] thumbnail(String name, int maxEdge) {
        byte[] original = photo(name);
        try {
            byte[] small = Jpegs.fit(original, maxEdge, 0.8f, false);
            return small != null ? small : original;
        } catch (IOException e) {
            return original;
        }
    }

    @Override
    public void dropPhoto(String name) {
        if (name == null) return;
        try {
            Files.deleteIfExists(resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A bare name only: it arrives from a URL. */
    private Path resolve(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.contains("..")) {
            throw new IllegalArgumentException("Not a shopping photo: " + name);
        }
        return photoDir.resolve(name);
    }

    @Override
    public synchronized List<BrandStore> brands() {
        return List.copyOf(brands);
    }

    @Override
    public synchronized void saveBrands(List<BrandStore> next) {
        try {
            Path tmp = brandsFile.resolveSibling("shopping-brands.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, brandsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            brands.clear();
            brands.addAll(next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<ShopPlace> places() {
        return List.copyOf(places);
    }

    @Override
    public synchronized void savePlaces(List<ShopPlace> next) {
        try {
            Path tmp = placesFile.resolveSibling("shopping-places.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, placesFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            places.clear();
            places.addAll(next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
