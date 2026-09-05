package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.model.StagedPhoto;
import family.eilertsen.rack.domain.model.FittedPhoto;
import family.eilertsen.rack.domain.port.PhotoStaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code data/staging/}: for each photograph waiting to be filed, the fitted
 * JPEG under its id, a small copy beside it for the strip, and a properties
 * file saying where the page was pointed when it was shot. Beside
 * {@code photos/} rather than in it, because a staged photograph is not one
 * the index knows about and the sweep of unreferenced photographs would
 * delete it on the next boot.
 */
@Component
public class FilesystemPhotoStaging implements PhotoStaging {

    private static final Logger log = LoggerFactory.getLogger(FilesystemPhotoStaging.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    /** The longest edge the vision model keeps; larger is downsampled on arrival, so keeping more is upload for nothing. */
    static final int PHOTO_EDGE = 1568;
    /** An 88px thumbnail on a 3× screen. */
    static final int PREVIEW_EDGE = 264;
    /** A batch left behind for longer than this was abandoned, not interrupted. */
    static final Duration KEEP = Duration.ofDays(7);

    private final Path dir;

    public FilesystemPhotoStaging(@Value("${rack.data-dir}") String dataDir) throws IOException {
        this.dir = Path.of(dataDir).toAbsolutePath().normalize().resolve("staging");
        Files.createDirectories(dir);
        int swept = sweep(KEEP);
        if (swept > 0) log.info("Dropped {} staged photograph(s) older than {}", swept, KEEP);
    }

    @Override
    public StagedPhoto stage(byte[] frame, String contentType, ContainerId container, SlotId slot) {
        FittedPhoto fitted = fit(frame, contentType);
        try {
            byte[] preview = Jpegs.fit(fitted.bytes(), PREVIEW_EDGE, 0.8f, false);
            String id = freshId();
            Properties where = new Properties();
            if (container != null) where.setProperty("container", container.value());
            if (slot != null) where.setProperty("slot", slot.value());
            // Written in the order they are looked for on the way back: the
            // record last, so a crash between files leaves a stray to sweep
            // rather than a record with no photograph behind it.
            writeAtomically(photo(id), fitted.bytes());
            writeAtomically(previewOf(id), preview == null ? fitted.bytes() : preview);
            try (var out = Files.newOutputStream(record(id))) {
                where.store(out, null);
            }
            return new StagedPhoto(id, Instant.now(), container, slot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public FittedPhoto fit(byte[] frame, String contentType) {
        try {
            byte[] jpeg = Jpegs.fit(frame, PHOTO_EDGE, 0.85f, true);
            return jpeg == null ? new FittedPhoto(frame, contentType) : new FittedPhoto(jpeg, "image/jpeg");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<StagedPhoto> all() {
        try (Stream<Path> files = Files.list(dir)) {
            List<StagedPhoto> staged = new ArrayList<>();
            for (Path p : files.filter(FilesystemPhotoStaging::isPhoto).sorted().toList()) {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                staged.add(describe(id, Files.getLastModifiedTime(p)));
            }
            return staged;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(String id) {
        return bytes(photo(id), id);
    }

    @Override
    public byte[] preview(String id) {
        return bytes(previewOf(id), id);
    }

    @Override
    public void remove(String id) {
        try {
            Files.deleteIfExists(photo(id));
            Files.deleteIfExists(previewOf(id));
            Files.deleteIfExists(record(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int sweep(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);
        int swept = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(p);
                    if (isPhoto(p)) swept++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return swept;
    }

    private StagedPhoto describe(String id, FileTime at) throws IOException {
        Properties where = new Properties();
        Path record = record(id);
        if (Files.exists(record)) {
            try (var in = Files.newInputStream(record)) {
                where.load(in);
            }
        }
        String container = where.getProperty("container");
        String slot = where.getProperty("slot");
        return new StagedPhoto(id, at.toInstant(),
            container == null ? null : new ContainerId(container),
            slot == null ? null : new SlotId(slot));
    }

    private byte[] bytes(Path p, String id) {
        try {
            return Files.readAllBytes(p);
        } catch (NoSuchFileException gone) {
            throw new NoSuchElementException("No staged photo " + id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isPhoto(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && name.endsWith(".jpg") && !name.endsWith(".preview.jpg");
    }

    private String freshId() {
        for (int n = 0; n < 1000; n++) {
            String id = LocalDateTime.now().format(STAMP) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
            if (!Files.exists(photo(id))) return id;
        }
        throw new IllegalStateException("Cannot find a free staging id in " + dir);
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path photo(String id) { return dir.resolve(valid(id) + ".jpg"); }
    private Path previewOf(String id) { return dir.resolve(valid(id) + ".preview.jpg"); }
    private Path record(String id) { return dir.resolve(valid(id) + ".properties"); }

    /** An id reaches here from a URL; a plain name cannot walk out of the folder. */
    private static String valid(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Not a staging id: " + id);
        return id;
    }
}
