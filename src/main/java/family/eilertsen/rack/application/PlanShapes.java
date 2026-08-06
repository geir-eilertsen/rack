package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ProjectPart;
import family.eilertsen.rack.domain.model.ProjectStep;
import family.eilertsen.rack.domain.model.SlotId;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire shapes a checklist and a plan arrive in, and how they become domain
 * objects.
 *
 * <p>Shared by {@link StartProject} and {@link AdoptPlan} because they are the
 * same conversion arriving at different doors: one starts a project with it, the
 * other folds it into a project that already exists. Two copies of this would
 * drift, and the drift would show up as a part that has a supplier on one path
 * and not the other.
 */
public final class PlanShapes {

    private PlanShapes() {
    }

    public static List<ProjectPart> parts(List<Line> lines) {
        List<ProjectPart> parts = new ArrayList<>();
        for (Line line : lines == null ? List.<Line>of() : lines) {
            if (line == null || line.part() == null || line.part().isBlank()) continue;
            parts.add(new ProjectPart(line.part().strip(), line.qty(),
                ProjectPart.isStatus(line.status()) ? line.status() : ProjectPart.TO_BUY,
                line.supplier(), line.search(), line.code(), line.note(),
                sources(line.from()), null));
        }
        return List.copyOf(parts);
    }

    public static List<ProjectStep> steps(List<Step> given) {
        List<ProjectStep> steps = new ArrayList<>();
        for (Step step : given == null ? List.<Step>of() : given) {
            if (step == null || step.title() == null || step.title().isBlank()) continue;
            steps.add(new ProjectStep(step.title().strip(), step.detail(),
                sources(step.uses()), false, null, null));
        }
        return List.copyOf(steps);
    }

    public static List<ProjectPart.ProjectSource> sources(List<Source> given) {
        List<ProjectPart.ProjectSource> out = new ArrayList<>();
        for (Source s : given == null ? List.<Source>of() : given) {
            if (s == null || s.container() == null || s.slot() == null) continue;
            try {
                out.add(new ProjectPart.ProjectSource(
                    new ContainerId(s.container().strip()), new SlotId(s.slot().strip()), s.item()));
            } catch (IllegalArgumentException e) {
                // A malformed location is not worth refusing a whole plan over.
            }
        }
        return List.copyOf(out);
    }

    public record Source(String container, String slot, String item) {}

    public record Line(String part, String qty, String status, String supplier,
                String search, String code, String note, List<Source> from) {}

    public record Step(String title, String detail, List<Source> uses) {}
}
