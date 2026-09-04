package family.eilertsen.rack.adapter.out.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemImageStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void aThumbnailIsNoLongerThanAskedOnItsLongerSideAndIsKeptForNextTime() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());
        String name = store.store(jpeg(1600, 1200), "image/jpeg");

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(store.thumbnail(name, 160)));

        assertThat(thumb.getWidth()).isEqualTo(160);
        assertThat(thumb.getHeight()).isEqualTo(120);
        assertThat(dataDir.resolve("thumbs/160").resolve(name + ".jpg")).exists();
        // A thumbnail is not a photograph nothing points at.
        assertThat(store.all()).containsExactly(name);
    }

    @Test
    void aThumbnailGoesWithItsPhotographAndAStrayOneIsSweptAtBoot() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());
        String kept = store.store(jpeg(400, 300), "image/jpeg");
        String gone = store.store(jpeg(400, 300), "image/jpeg");
        store.thumbnail(kept, 160);
        store.thumbnail(gone, 160);

        store.delete(gone);
        assertThat(dataDir.resolve("thumbs/160").resolve(gone + ".jpg")).doesNotExist();

        Files.delete(dataDir.resolve("photos").resolve(kept));
        new FilesystemImageStore(dataDir.toString());
        assertThat(dataDir.resolve("thumbs/160").resolve(kept + ".jpg")).doesNotExist();
    }

    @Test
    void aFormatItCannotScaleIsServedAsItWas() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());
        byte[] notAnImage = "RIFF....WEBP".getBytes(StandardCharsets.UTF_8);
        String name = store.store(notAnImage, "image/webp");

        assertThat(store.thumbnail(name, 160)).isEqualTo(notAnImage);
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void aBatchStoredWithinOneSecondGetsDistinctNames() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        List<String> names = List.of(
            store.store("front".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store("label".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store("side".getBytes(StandardCharsets.UTF_8), "image/jpeg"));

        assertThat(names).doesNotHaveDuplicates();
        assertThat(store.read(names.get(1))).isEqualTo("label".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void namesAreUniqueAcrossTheWholeRackNowThatOneFolderHoldsEverything() throws IOException {
        // Two drawers photographed in the same second used to be kept apart by
        // their directories. They are not any more.
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        List<String> names = List.of(
            store.store("drawer A".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store("drawer B".getBytes(StandardCharsets.UTF_8), "image/jpeg"));

        assertThat(names).doesNotHaveDuplicates();
        assertThat(store.read(names.get(0))).isEqualTo("drawer A".getBytes(StandardCharsets.UTF_8));
        assertThat(store.read(names.get(1))).isEqualTo("drawer B".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void suffixedNamesSortAfterTheBareOneSoCaptureOrderSurvives() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        List<String> names = List.of(
            store.store("one".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store("two".getBytes(StandardCharsets.UTF_8), "image/jpeg"));

        assertThat(names).isSorted();
    }

    @Test
    void everythingLandsInOneFolder() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());
        String name = store.store("a frame".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        assertThat(dataDir.resolve("photos").resolve(name)).exists();
    }

    @Test
    void deletingDropsTheFrameAndShrugsAtOneThatHasAlreadyGone() throws IOException {
        // A resync drops the frames it replaced. Running it twice, or after
        // someone tidied the directory by hand, asks for a file that is already
        // absent — which is the state being asked for, not a failure.
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());
        String name = store.store("old".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        store.delete(name);
        store.delete(name);
        store.delete("never-existed.jpg");

        assertThat(Files.exists(dataDir.resolve("photos").resolve(name))).isFalse();
    }

    @Test
    void refusesAFilenameThatWouldWalkOutOfTheFolder() throws IOException {
        // The name arrives from a URL now that photos are served flat, so "../"
        // would leave the data directory rather than merely land in the next
        // drawer.
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        for (String hostile : List.of("../secrets.txt", "sub/dir.jpg", "..", "")) {
            assertThatThrownBy(() -> store.read(hostile)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.delete(hostile)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readingSomethingThatIsNotThereFails() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        assertThatThrownBy(() -> store.read("never-existed.jpg")).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void keepsTheExtensionForTheContentType() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        assertThat(store.store(new byte[]{1}, "image/png")).endsWith(".png");
        assertThat(store.store(new byte[]{1}, null)).endsWith(".jpg");
    }
}
