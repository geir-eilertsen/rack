package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.model.ProjectNote;
import family.eilertsen.rack.domain.model.ProjectPart;
import family.eilertsen.rack.domain.model.ProjectStep;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Folds a checklist or a plan into a project that already exists.
 *
 * <p>The gap this fills: a project named by hand starts empty, and until now the
 * only way to get parts and steps was to begin at the ask page and let it create
 * a <em>new</em> project. So the help lived somewhere other than the thing it
 * helps with.
 *
 * <p><strong>It appends and never rewrites.</strong> By the time you ask for
 * advice a second time some parts are ordered, some steps are ticked and some
 * carry a note about a lifted pad — none of which a fresh plan knows or could
 * reproduce. So nothing here replaces a part, a step, a status or a note. What
 * arrives is added; what was already there is left exactly as it was, and
 * deciding a proposed line is a duplicate is the user's to make on screen, where
 * they can see both.
 *
 * <p>Cautions are the one exception, and only because they are plain strings with
 * no state to lose: an identical one is not added twice.
 */
@Service
public class AdoptPlan {

    private final Projects projects;

    public AdoptPlan(Projects projects) {
        this.projects = projects;
    }

    public Project execute(ProjectId id, Adoption adoption) {
        Project p = projects.get(id).orElseThrow(() ->
            new NoSuchElementException("No such project: " + id.value()));
        if (adoption == null) return p;

        List<ProjectPart> newParts = PlanShapes.parts(adoption.parts());
        List<ProjectStep> newSteps = PlanShapes.steps(adoption.steps());
        List<String> newCautions = adoption.cautions() == null ? List.of() : adoption.cautions();
        if (newParts.isEmpty() && newSteps.isEmpty() && newCautions.isEmpty()) return p;

        List<ProjectPart> parts = new ArrayList<>(p.parts());
        parts.addAll(newParts);
        List<ProjectStep> steps = new ArrayList<>(p.steps());
        steps.addAll(newSteps);

        // A set, because the same hazard proposed twice is still one hazard, and
        // unlike a part it carries nothing that could differ.
        LinkedHashSet<String> cautions = new LinkedHashSet<>(p.cautions());
        for (String c : newCautions) if (c != null && !c.isBlank()) cautions.add(c.strip());

        List<ProjectNote> log = new ArrayList<>(p.log());
        log.add(ProjectNote.app(describe(newParts.size(), newSteps.size(),
            cautions.size() - p.cautions().size())));

        // Nothing outstanding before and something to buy now means the shopping
        // has started. Same rule the parts already follow.
        String status = Project.PLANNING.equals(p.status())
            && parts.stream().anyMatch(ProjectPart::outstanding) ? Project.SHOPPING : p.status();

        Project updated = new Project(p.id(), p.name(), p.brief(), status, p.startedAt(),
            Instant.now(), p.finishedAt(), List.copyOf(parts), List.copyOf(steps),
            List.copyOf(cautions), List.copyOf(log), p.documents());
        projects.save(updated);
        return updated;
    }

    private static String describe(int parts, int steps, int cautions) {
        List<String> bits = new ArrayList<>();
        if (parts > 0) bits.add(parts + (parts == 1 ? " part" : " parts"));
        if (steps > 0) bits.add(steps + (steps == 1 ? " step" : " steps"));
        if (cautions > 0) bits.add(cautions + (cautions == 1 ? " caution" : " cautions"));
        return "Took in " + String.join(", ", bits) + " from the model.";
    }

    public record Adoption(List<PlanShapes.Line> parts, List<PlanShapes.Step> steps,
                           List<String> cautions) {}
}
