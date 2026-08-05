package family.eilertsen.rack.adapter.out.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
