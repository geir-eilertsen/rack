package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemImageStoreTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId A1 = new SlotId("A1");

    @TempDir
    Path dataDir;

    @Test
    void aBatchStoredWithinOneSecondGetsDistinctNames() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        List<String> names = List.of(
            store.store(RACK, A1, "front".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store(RACK, A1, "label".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store(RACK, A1, "side".getBytes(StandardCharsets.UTF_8), "image/jpeg"));

        assertThat(names).doesNotHaveDuplicates();
        assertThat(store.list(RACK, A1)).containsExactlyInAnyOrderElementsOf(names);
        assertThat(store.read(RACK, A1, names.get(1))).isEqualTo("label".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void suffixedNamesSortAfterTheBareOneSoCaptureOrderSurvives() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        List<String> names = List.of(
            store.store(RACK, A1, "one".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
            store.store(RACK, A1, "two".getBytes(StandardCharsets.UTF_8), "image/jpeg"));

        assertThat(store.list(RACK, A1)).isEqualTo(names);
    }

    @Test
    void deletingDropsTheFrameAndShrugsAtOneThatHasAlreadyGone() throws IOException {
        // A resync drops the frames it replaced. Running it twice, or after
        // someone tidied the directory by hand, asks for a file that is already
        // absent — which is the state being asked for, not a failure.
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());
        String name = store.store(RACK, A1, "old".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        store.delete(RACK, A1, name);
        store.delete(RACK, A1, name);
        store.delete(RACK, A1, "never-existed.jpg");

        assertThat(store.list(RACK, A1)).isEmpty();
    }

    @Test
    void keepsTheExtensionForTheContentType() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        assertThat(store.store(RACK, A1, new byte[]{1}, "image/png")).endsWith(".png");
        assertThat(store.store(RACK, A1, new byte[]{1}, null)).endsWith(".jpg");
    }
}
