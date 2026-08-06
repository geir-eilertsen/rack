package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.port.DocumentStore;
import family.eilertsen.rack.domain.port.ProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeepDocumentsTest {

    private FakeDocs store;
    private Projects projects;
    private StartProject start;
    private KeepDocuments keep;
    private ForgetUnusedDocuments sweep;

    @BeforeEach
    void setUp() {
        store = new FakeDocs();
        projects = new Projects(new FakeProjects());
        projects.load();
        start = new StartProject(projects);
        keep = new KeepDocuments(projects, store);
        sweep = new ForgetUnusedDocuments(keep, store);
    }

    @Test
    void keepsAManualWithAReadableTitleTakenFromTheFilename() {
        Project p = newProject("Quad 606 restoration");

        p = keep.attach(p.id(), bytes("manual"), "quad_606-service_manual.pdf", "application/pdf", null);

        assertThat(p.documents()).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("quad 606 service manual");
            assertThat(d.contentType()).isEqualTo("application/pdf");
            assertThat(d.size()).isEqualTo(bytes("manual").length);
        });
        assertThat(p.log()).last().extracting(n -> n.text())
            .isEqualTo("Kept a document: quad 606 service manual");
    }

    @Test
    void anExplicitTitleWinsOverTheFilename() {
        Project p = newProject("Quad 606");
        p = keep.attach(p.id(), bytes("m"), "scan001.pdf", "application/pdf", "Service manual, pages 1-40");

        assertThat(p.documents().get(0).title()).isEqualTo("Service manual, pages 1-40");
    }

    @Test
    void removingADocumentTakesTheFileWithIt() {
        Project p = newProject("Quad 606");
        p = keep.attach(p.id(), bytes("m"), "manual.pdf", "application/pdf", null);
        String filename = p.documents().get(0).filename();

        p = keep.detach(p.id(), filename);

        assertThat(p.documents()).isEmpty();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void aManualTwoProjectsKeepSurvivesOneOfThemDroppingIt() {
        // Two jobs on the same amplifier reasonably hold the same manual, and one
        // of them finishing says nothing about the other.
        Project a = newProject("Quad 606 left channel");
        Project b = newProject("Quad 606 right channel");
        a = keep.attach(a.id(), bytes("m"), "manual.pdf", "application/pdf", null);
        String shared = a.documents().get(0).filename();
        // The second project points at the same stored file.
        b = keep.attach(b.id(), bytes("m"), "manual.pdf", "application/pdf", null);
        String other = b.documents().get(0).filename();
        assertThat(other).isNotEqualTo(shared);   // separate uploads are separate files

        keep.detach(a.id(), shared);

        assertThat(store.all()).containsExactly(other);
    }

    @Test
    void deletingAProjectTakesItsDocumentsWithIt() {
        Project p = newProject("Quad 606");
        p = keep.attach(p.id(), bytes("m"), "manual.pdf", "application/pdf", null);
        List<family.eilertsen.rack.domain.model.ProjectDocument> held = p.documents();

        projects.remove(p.id());
        List<String> gone = keep.forget(held);

        assertThat(gone).hasSize(1);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void theSweepClearsADocumentNoProjectNames() {
        Project p = newProject("Quad 606");
        keep.attach(p.id(), bytes("m"), "kept.pdf", "application/pdf", null);
        store.store(bytes("stray"), "stray.pdf", "application/pdf");

        assertThat(sweep.sweep()).containsExactly("stray.pdf");
        assertThat(store.all()).containsExactly("kept.pdf");
    }

    @Test
    void retitlingChangesTheHeadingAndNotTheFile() {
        Project p = newProject("Quad 606");
        p = keep.attach(p.id(), bytes("m"), "scan001.pdf", "application/pdf", null);
        String filename = p.documents().get(0).filename();

        p = keep.retitle(p.id(), filename, "Service manual");

        assertThat(p.documents().get(0).title()).isEqualTo("Service manual");
        assertThat(p.documents().get(0).filename()).isEqualTo(filename);
        assertThat(store.all()).containsExactly(filename);
    }

    @Test
    void refusesToActOnADocumentTheProjectDoesNotHave() {
        Project p = newProject("Quad 606");

        assertThatThrownBy(() -> keep.detach(p.id(), "nothing.pdf"))
            .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> keep.retitle(p.id(), "nothing.pdf", "x"))
            .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> keep.attach(p.id(), new byte[0], "empty.pdf", "application/pdf", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProjectFileWrittenBeforeDocumentsExistedReadsAsHavingNone() {
        // No migration, per the storage model: the absent field is no documents.
        Project bare = new Project(new ProjectId("old"), "Old job", null, Project.BUILDING,
            null, null, null, List.of(), List.of(), List.of(), List.of(), null);

        assertThat(bare.documents()).isEmpty();
    }

    private Project newProject(String name) {
        return start.execute(new StartProject.Request(name, null, List.of(), List.of(), List.of()));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeDocs implements DocumentStore {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        @Override
        public String store(byte[] bytes, String originalFilename, String contentType) {
            if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("the document is empty");
            String name = originalFilename == null ? "document" : originalFilename;
            String candidate = name;
            for (int n = 2; files.containsKey(candidate); n++) candidate = n + "-" + name;
            files.put(candidate, bytes);
            return candidate;
        }

        @Override
        public byte[] read(String filename) {
            return files.get(filename);
        }

        @Override
        public List<String> all() {
            return new ArrayList<>(files.keySet());
        }

        @Override
        public void delete(String filename) {
            files.remove(filename);
        }
    }

    private static final class FakeProjects implements ProjectStore {
        private final Map<ProjectId, Project> saved = new LinkedHashMap<>();

        @Override
        public List<Project> loadAll() {
            return List.copyOf(saved.values());
        }

        @Override
        public void save(Project project) {
            saved.put(project.id(), project);
        }

        @Override
        public void delete(ProjectId id) {
            saved.remove(id);
        }
    }
}
