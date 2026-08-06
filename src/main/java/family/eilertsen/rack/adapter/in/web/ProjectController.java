package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AdoptPlan;
import family.eilertsen.rack.application.KeepDocuments;
import family.eilertsen.rack.application.Projects;
import family.eilertsen.rack.application.RunProject;
import family.eilertsen.rack.application.SettleProject;
import family.eilertsen.rack.application.StartProject;
import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.Document;
import family.eilertsen.rack.domain.model.ProjectId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final Projects projects;
    private final StartProject start;
    private final RunProject run;
    private final SettleProject settle;
    private final KeepDocuments docs;
    private final AdoptPlan adopt;

    public ProjectController(Projects projects, StartProject start, RunProject run,
                             SettleProject settle, KeepDocuments docs, AdoptPlan adopt) {
        this.projects = projects;
        this.start = start;
        this.run = run;
        this.settle = settle;
        this.docs = docs;
        this.adopt = adopt;
    }

    /** The list page's whole payload: enough per project to show a card, no more. */
    @GetMapping
    public List<Summary> all() {
        return projects.all().stream().map(Summary::of).toList();
    }

    @PostMapping
    public Project create(@RequestBody StartProject.Request body) {
        return start.execute(body);
    }

    @GetMapping("/{id}")
    public Project one(@PathVariable String id) {
        return projects.get(new ProjectId(id))
            .orElseThrow(() -> new NoSuchElementException("No such project: " + id));
    }

    @PatchMapping("/{id}")
    public Project update(@PathVariable String id, @RequestBody Patch body) {
        ProjectId pid = new ProjectId(id);
        Project current = null;
        if (body.name() != null) current = run.rename(pid, body.name());
        if (body.status() != null) current = run.setStatus(pid, body.status());
        return current != null ? current : one(id);
    }

    /** Takes the project's documents with it, unless another project keeps them. */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        ProjectId pid = new ProjectId(id);
        List<Document> held = one(id).documents();
        run.delete(pid);
        docs.forget(held);
    }

    /**
     * Folds a checklist or a plan into this project. The advice itself still
     * comes from {@code /ask} and {@code /plan} — this is only where it lands, so
     * that asking for help on a project does not have to mean starting a new one.
     */
    @PostMapping("/{id}/adopt")
    public Project adopt(@PathVariable String id, @RequestBody AdoptPlan.Adoption body) {
        return adopt.execute(new ProjectId(id), body);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Project addDocument(@PathVariable String id,
                               @RequestParam("document") MultipartFile file,
                               @RequestParam(value = "title", required = false) String title) throws IOException {
        return docs.attach(new ProjectId(id), file.getBytes(),
            file.getOriginalFilename(), file.getContentType(), title);
    }

    /** Records where to look. Nothing is fetched — rack cannot reach the web. */
    @PostMapping("/{id}/links")
    public Project addLink(@PathVariable String id, @RequestBody Link body) {
        return docs.linkOnProject(new ProjectId(id), body.url(), body.title());
    }

    /**
     * {@code ref} is the stored filename for a file and the address for a link.
     * A query parameter rather than a path variable because an address has
     * slashes in it and a path variable cannot carry them.
     */
    @PatchMapping("/{id}/documents")
    public Project retitleDocument(@PathVariable String id, @RequestParam String ref,
                                   @RequestBody Title body) {
        return docs.retitle(new ProjectId(id), ref, body.title());
    }

    @DeleteMapping("/{id}/documents")
    public Project removeDocument(@PathVariable String id, @RequestParam String ref) {
        return docs.detach(new ProjectId(id), ref);
    }

    @PatchMapping("/{id}/steps/{index}")
    public Project step(@PathVariable String id, @PathVariable int index, @RequestBody StepPatch body) {
        ProjectId pid = new ProjectId(id);
        Project current = null;
        if (body.note() != null) current = run.noteStep(pid, index, body.note());
        if (body.done() != null) current = run.tickStep(pid, index, body.done());
        return current != null ? current : one(id);
    }

    @PatchMapping("/{id}/parts/{index}")
    public Project part(@PathVariable String id, @PathVariable int index, @RequestBody PartPatch body) {
        return run.setPartStatus(new ProjectId(id), index, body.status(), body.usedQty());
    }

    @PostMapping("/{id}/notes")
    public Project note(@PathVariable String id, @RequestBody Note body) {
        return run.addNote(new ProjectId(id), body.text());
    }

    /**
     * What settling up would take out of the drawers. A GET because it changes
     * nothing — the same split {@code /resync/preview} uses, for the same reason:
     * what you confirm is a removal.
     */
    @GetMapping("/{id}/settle")
    public SettleProject.Settlement settlement(@PathVariable String id) {
        return settle.preview(new ProjectId(id));
    }

    @PostMapping("/{id}/settle")
    public SettleProject.Result settleUp(@PathVariable String id, @RequestBody(required = false) SettleRequest body) {
        return settle.settle(new ProjectId(id), body == null || body.finish() == null || body.finish());
    }

    public record Patch(String name, String status) {}

    public record StepPatch(Boolean done, String note) {}

    public record PartPatch(String status, Integer usedQty) {}

    public record Note(String text) {}

    public record Title(String title) {}

    public record Link(String url, String title) {}

    public record SettleRequest(Boolean finish) {}

    /** Counts rather than contents, so the list page is one request. */
    public record Summary(String id, String name, String status, java.time.Instant startedAt,
                          java.time.Instant updatedAt, java.time.Instant finishedAt,
                          int parts, int stillToBuy, int steps, int stepsDone, String next) {
        static Summary of(Project p) {
            return new Summary(p.id().value(), p.name(), p.status(), p.startedAt(), p.updatedAt(),
                p.finishedAt(), p.parts().size(), p.stillToBuy(), p.steps().size(), p.stepsDone(),
                p.next() == null ? null : p.next().title());
        }
    }
}
