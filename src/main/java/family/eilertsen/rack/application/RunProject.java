package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.model.ProjectNote;
import family.eilertsen.rack.domain.model.ProjectPart;
import family.eilertsen.rack.domain.model.ProjectStep;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The small edits a project accumulates while it is being run: a step ticked, a
 * part marked ordered, a note written down.
 *
 * <p><strong>Every one of them writes to the log.</strong> That is most of what
 * makes a project worth storing — six weeks later "when did the transistors
 * arrive" and "why did I skip step nine" are the questions, and neither is
 * answerable from the current state alone. The status of a part tells you where
 * it is; the log tells you how it got there.
 *
 * <p>Status moves on its own where the answer is not a matter of opinion: tick
 * the last outstanding part and the shopping is done, tick the first step and the
 * building has started. Explicit moves are still allowed, because parking a job
 * halfway is a decision no rule can infer.
 */
@Service
public class RunProject {

    private final Projects projects;

    public RunProject(Projects projects) {
        this.projects = projects;
    }

    public Project tickStep(ProjectId id, int index, boolean done) {
        Project p = require(id);
        List<ProjectStep> steps = new ArrayList<>(p.steps());
        checkRange(index, steps.size(), "Step");
        ProjectStep step = steps.get(index);
        if (step.done() == done) return p;

        Instant now = Instant.now();
        steps.set(index, step.ticked(done, now));
        List<ProjectNote> log = append(p, (done ? "Done: " : "Reopened: ") + step.title());

        // Ticking a step does not end the shopping. The first steps of a job are
        // reading the manual and photographing the inside, both of which you do
        // while waiting for the post — a real run ticked step one with nineteen
        // parts unordered and the project called itself "building". Only the parts
        // decide that, in setPartStatus.
        //
        // Nor does ticking the last step finish a project: putting the lid back on
        // and knowing it works are different, and only the user can say the second.
        return saved(new Project(p.id(), p.name(), p.brief(), p.status(), p.startedAt(), now,
            p.finishedAt(), p.parts(), List.copyOf(steps), p.cautions(), log));
    }

    public Project noteStep(ProjectId id, int index, String note) {
        Project p = require(id);
        List<ProjectStep> steps = new ArrayList<>(p.steps());
        checkRange(index, steps.size(), "Step");
        steps.set(index, steps.get(index).annotated(note == null || note.isBlank() ? null : note.strip()));
        return saved(new Project(p.id(), p.name(), p.brief(), p.status(), p.startedAt(), Instant.now(),
            p.finishedAt(), p.parts(), List.copyOf(steps), p.cautions(), p.log()));
    }

    public Project setPartStatus(ProjectId id, int index, String status, Integer usedQty) {
        Project p = require(id);
        if (!ProjectPart.isStatus(status)) {
            throw new IllegalArgumentException("Unknown part status: " + status
                + " (expected one of " + ProjectPart.statuses() + ")");
        }
        List<ProjectPart> parts = new ArrayList<>(p.parts());
        checkRange(index, parts.size(), "Part");
        ProjectPart part = parts.get(index);
        parts.set(index, part.withStatus(status.strip(), usedQty));

        List<ProjectNote> log = append(p, part.part() + " → " + status.strip().replace('_', ' '));
        boolean wasShopping = Project.SHOPPING.equals(p.status());
        boolean nothingLeft = parts.stream().noneMatch(ProjectPart::outstanding);
        String newStatus = wasShopping && nothingLeft ? Project.BUILDING : p.status();
        if (!newStatus.equals(p.status())) log = append(log, "Everything is here — on to the build.");

        return saved(new Project(p.id(), p.name(), p.brief(), newStatus, p.startedAt(), Instant.now(),
            p.finishedAt(), List.copyOf(parts), p.steps(), p.cautions(), log));
    }

    public Project setStatus(ProjectId id, String status) {
        Project p = require(id);
        if (!Project.isStatus(status)) {
            throw new IllegalArgumentException("Unknown project status: " + status
                + " (expected one of " + Project.statuses() + ")");
        }
        String next = status.strip();
        if (next.equals(p.status())) return p;
        Instant now = Instant.now();
        // Reopening clears the finish date rather than keeping a date that is no
        // longer true of anything.
        Instant finished = Project.DONE.equals(next) ? now : null;
        return saved(new Project(p.id(), p.name(), p.brief(), next, p.startedAt(), now, finished,
            p.parts(), p.steps(), p.cautions(), append(p, "Status: " + p.status() + " → " + next)));
    }

    public Project rename(ProjectId id, String name) {
        Project p = require(id);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        return saved(new Project(p.id(), name.strip(), p.brief(), p.status(), p.startedAt(),
            Instant.now(), p.finishedAt(), p.parts(), p.steps(), p.cautions(), p.log()));
    }

    public Project addNote(ProjectId id, String text) {
        Project p = require(id);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("a note needs some text");
        List<ProjectNote> log = new ArrayList<>(p.log());
        log.add(ProjectNote.user(text.strip()));
        return saved(new Project(p.id(), p.name(), p.brief(), p.status(), p.startedAt(), Instant.now(),
            p.finishedAt(), p.parts(), p.steps(), p.cautions(), List.copyOf(log)));
    }

    public void delete(ProjectId id) {
        require(id);
        projects.remove(id);
    }

    private Project require(ProjectId id) {
        return projects.get(id).orElseThrow(() ->
            new NoSuchElementException("No such project: " + id.value()));
    }

    private Project saved(Project p) {
        projects.save(p);
        return p;
    }

    private static void checkRange(int index, int size, String what) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(what + " index " + index + " out of range (0.." + (size - 1) + ")");
        }
    }

    private static List<ProjectNote> append(Project p, String text) {
        return append(p.log(), text);
    }

    private static List<ProjectNote> append(List<ProjectNote> log, String text) {
        List<ProjectNote> out = new ArrayList<>(log);
        out.add(ProjectNote.app(text));
        return List.copyOf(out);
    }
}
