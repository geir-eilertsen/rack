package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.Projects;
import family.eilertsen.rack.application.RunProject;
import family.eilertsen.rack.application.SettleProject;
import family.eilertsen.rack.application.StartProject;
import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
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

    public ProjectController(Projects projects, StartProject start, RunProject run, SettleProject settle) {
        this.projects = projects;
        this.start = start;
        this.run = run;
        this.settle = settle;
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

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        run.delete(new ProjectId(id));
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
