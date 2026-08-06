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

/**
 * Turns an answer and a plan into something that persists.
 *
 * <p>Taken from what the user already has on screen rather than asked for again.
 * They have paid for the question and the plan and read both; making the model
 * produce them a third time would cost money to arrive at a different answer,
 * and a plan you have read is the one you want kept.
 *
 * <p>A project can also start empty. Not everything begins with a question — you
 * may know perfectly well what you are doing and want somewhere to keep it.
 */
@Service
public class StartProject {

    private final Projects projects;

    public StartProject(Projects projects) {
        this.projects = projects;
    }

    public Project execute(Request request) {
        String name = request.name() == null || request.name().isBlank()
            ? request.brief() : request.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a project needs a name or a brief");
        }

        ProjectId id = projects.freeId(shorten(name));
        Instant now = Instant.now();

        List<ProjectPart> parts = PlanShapes.parts(request.parts());
        List<ProjectStep> steps = PlanShapes.steps(request.steps());

        // Where it starts is a fact about the parts, not a choice: nothing to buy
        // means the shopping is already done.
        String status = parts.stream().anyMatch(ProjectPart::outstanding)
            ? Project.SHOPPING : (steps.isEmpty() ? Project.PLANNING : Project.BUILDING);

        Project project = new Project(id, shorten(name).strip(), request.brief(), status,
            now, now, null, List.copyOf(parts), List.copyOf(steps),
            request.cautions() == null ? List.of() : List.copyOf(request.cautions()),
            List.of(ProjectNote.app("Project started — " + parts.size() + " parts, " + steps.size() + " steps.")),
            List.of());

        projects.save(project);
        return project;
    }

    /**
     * A name for a heading, out of a sentence typed at a question box.
     *
     * <p>"I am restoring a Quad 606 amplifier. Do I have all the parts?" is a fine
     * brief and a poor title, so the first sentence stands in and the brief keeps
     * the whole of it. The user can rename it; this only has to be reasonable.
     */
    private static String shorten(String name) {
        String first = name.strip().split("[.?!\\n]")[0].strip();
        if (first.isEmpty()) first = name.strip();
        return first.length() <= 72 ? first : first.substring(0, 72).strip();
    }

    public record Request(String name, String brief, List<PlanShapes.Line> parts,
                          List<PlanShapes.Step> steps, List<String> cautions) {}
}
