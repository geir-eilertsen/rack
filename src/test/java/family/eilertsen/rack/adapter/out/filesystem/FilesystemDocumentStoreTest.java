package family.eilertsen.rack.adapter.out.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemDocumentStoreTest {

    @TempDir
    Path dataDir;

    private FilesystemDocumentStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new FilesystemDocumentStore(dataDir.toString());
    }

    @Test
    void keepsTheNameYouUploadedBecauseATimestampSaysNothing() {
        // "Quad 405-2 606 707 service manual.pdf" is worth recognising in a
        // directory listing six months later. "2026-08-06-0912.pdf" is not.
        String name = store.store(pdf(), "Quad 405-2 606 707 service manual.pdf", "application/pdf");

        assertThat(name).isEqualTo("Quad-405-2-606-707-service-manual.pdf");
        assertThat(store.read(name)).isEqualTo(pdf());
    }

    @Test
    void twoRevisionsOfOneManualAreTwoDocuments() {
        String first = store.store(pdf(), "manual.pdf", "application/pdf");
        String second = store.store("newer".getBytes(StandardCharsets.UTF_8), "manual.pdf", "application/pdf");

        assertThat(first).isEqualTo("manual.pdf");
        assertThat(second).isEqualTo("manual_2.pdf");
        assertThat(store.read(first)).isNotEqualTo(store.read(second));
    }

    @Test
    void takesTheExtensionFromTheDeclaredTypeWhenTheUploadHasNone() {
        assertThat(store.store(pdf(), "scan", "application/pdf")).isEqualTo("scan.pdf");
        assertThat(store.store(pdf(), "board", "image/jpeg")).isEqualTo("board.jpg");
    }

    @Test
    void refusesANameThatWouldWalkOutOfTheDataDirectory() {
        // One folder for the whole rack, so a ../ leaves the data directory rather
        // than merely landing in the next project.
        for (String bad : new String[] {"../secrets.txt", "sub/dir.pdf", "..", "a\\b.pdf"}) {
            assertThatThrownBy(() -> store.read(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a document name");
        }
        // And a path offered as the upload's own name is stripped to its last part.
        assertThat(store.store(pdf(), "/etc/passwd", "text/plain")).isEqualTo("passwd.txt");
    }

    @Test
    void worksWhenTheDataDirectoryIsGivenRelatively() throws IOException {
        // How it is actually configured: rack.data-dir is "./data". The "."
        // survives toAbsolutePath() and does not survive normalize(), so a store
        // that normalised only one side of its own guard rejected every legal
        // filename in the container while passing every test here — @TempDir
        // hands out a path with no "." in it.
        FilesystemDocumentStore relative =
            new FilesystemDocumentStore(dataDir + "/./sub/../nested");

        String name = relative.store(pdf(), "manual.pdf", "application/pdf");

        assertThat(relative.read(name)).isEqualTo(pdf());
        assertThat(relative.all()).containsExactly("manual.pdf");
    }

    @Test
    void readingSomethingThatIsNotThereSaysSo() {
        assertThatThrownBy(() -> store.read("absent.pdf")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listsAndDeletes() {
        store.store(pdf(), "a.pdf", "application/pdf");
        store.store(pdf(), "b.pdf", "application/pdf");

        assertThat(store.all()).containsExactly("a.pdf", "b.pdf");

        store.delete("a.pdf");
        assertThat(store.all()).containsExactly("b.pdf");
        // Deleting what is already gone is not an error — the sweep runs on a set
        // that may have changed underneath it.
        store.delete("a.pdf");
    }

    @Test
    void refusesAnEmptyDocument() {
        assertThatThrownBy(() -> store.store(new byte[0], "empty.pdf", "application/pdf"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] pdf() {
        return "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
    }
}
