package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectDocument;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.model.ProjectNote;
import family.eilertsen.rack.domain.port.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Attaches the manual to the job, and takes it away again.
 *
 * <p>Step one of the Quad 606 plan is "download the service manual and
 * schematic". The point of keeping it here is that step one never has to happen
 * twice — and that the schematic is beside the drawer links and the notes rather
 * than in whichever downloads folder it landed in.
 *
 * <p>Documents live in one flat folder for the whole rack, the way photographs
 * do, so the same rule applies: a file goes only when nothing points at it. Two
 * projects on the same amplifier may reasonably keep the same manual, and one of
 * them finishing says nothing about the other.
 */
@Service
public class KeepDocuments {

    private static final Logger log = LoggerFactory.getLogger(KeepDocuments.class);

    private final Projects projects;
    private final DocumentStore documents;

    public KeepDocuments(Projects projects, DocumentStore documents) {
        this.projects = projects;
        this.documents = documents;
    }

    public Project attach(ProjectId id, byte[] bytes, String originalFilename, String contentType, String title) {
        Project project = require(id);
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("the document is empty");

        String stored = documents.store(bytes, originalFilename, contentType);
        ProjectDocument doc = new ProjectDocument(stored,
            title == null || title.isBlank() ? cleanTitle(originalFilename) : title.strip(),
            contentType, bytes.length, Instant.now());

        List<ProjectDocument> kept = new ArrayList<>(project.documents());
        kept.add(doc);
        return saved(project, List.copyOf(kept), ProjectNote.app("Kept a document: " + doc.title()));
    }

    public Project detach(ProjectId id, String filename) {
        Project project = require(id);
        ProjectDocument going = project.documents().stream()
            .filter(d -> d.filename().equals(filename)).findFirst()
            .orElseThrow(() -> new NoSuchElementException("This project has no document " + filename));

        List<ProjectDocument> kept = new ArrayList<>(project.documents());
        kept.removeIf(d -> d.filename().equals(filename));
        Project updated = saved(project, List.copyOf(kept),
            ProjectNote.app("Removed a document: " + going.title()));

        // After the write, and against every project: the same manual may be kept
        // by another job on the same machine.
        if (!inUse().contains(filename)) documents.delete(filename);
        return updated;
    }

    public Project retitle(ProjectId id, String filename, String title) {
        Project project = require(id);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("a title is required");
        List<ProjectDocument> kept = new ArrayList<>();
        boolean found = false;
        for (ProjectDocument d : project.documents()) {
            if (d.filename().equals(filename)) { kept.add(d.retitled(title.strip())); found = true; }
            else kept.add(d);
        }
        if (!found) throw new NoSuchElementException("This project has no document " + filename);
        return saved(project, List.copyOf(kept), null);
    }

    /**
     * Every document any project names. The one question, with one place to ask
     * it — the same shape as {@code PartIndex.photosInUse}.
     */
    public Set<String> inUse() {
        Set<String> used = new HashSet<>();
        for (Project p : projects.all()) {
            for (ProjectDocument d : p.documents()) used.add(d.filename());
        }
        return used;
    }

    /**
     * Deletes the files a project was the last to hold.
     *
     * <p>Called after the project has gone from the registry, so {@link #inUse}
     * no longer counts it. A crash between the two leaves a file nothing points
     * at, which the boot sweep collects — the other order would leave a project
     * naming a document that is not there.
     */
    public List<String> forget(List<ProjectDocument> orphaned) {
        Set<String> stillUsed = inUse();
        List<String> gone = new ArrayList<>();
        for (ProjectDocument d : orphaned == null ? List.<ProjectDocument>of() : orphaned) {
            if (stillUsed.contains(d.filename())) continue;
            documents.delete(d.filename());
            gone.add(d.filename());
            log.info("Deleting document nothing references: {}", d.filename());
        }
        return List.copyOf(gone);
    }

    /** A filename is a poor heading; the name someone typed is a decent one. */
    private static String cleanTitle(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) return "Document";
        String name = originalFilename.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").strip();
        return name.isEmpty() ? "Document" : name;
    }

    private Project require(ProjectId id) {
        return projects.get(id).orElseThrow(() ->
            new NoSuchElementException("No such project: " + id.value()));
    }

    private Project saved(Project p, List<ProjectDocument> docs, ProjectNote note) {
        List<ProjectNote> logLines = new ArrayList<>(p.log());
        if (note != null) logLines.add(note);
        Project updated = new Project(p.id(), p.name(), p.brief(), p.status(), p.startedAt(),
            Instant.now(), p.finishedAt(), p.parts(), p.steps(), p.cautions(),
            List.copyOf(logLines), docs);
        projects.save(updated);
        return updated;
    }
}
