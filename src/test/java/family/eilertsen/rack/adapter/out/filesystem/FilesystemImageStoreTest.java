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
    void keepsTheExtensionForTheContentType() throws IOException {
        FilesystemImageStore store = new FilesystemImageStore(dataDir.toString());

        assertThat(store.store(RACK, A1, new byte[]{1}, "image/png")).endsWith(".png");
        assertThat(store.store(RACK, A1, new byte[]{1}, null)).endsWith(".jpg");
    }
}
