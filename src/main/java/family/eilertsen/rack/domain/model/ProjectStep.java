package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * One step of the job. {@code uses} points at drawers, so the step that fits the
 * output transistors links to the compound it needs rather than describing it.
 */
public record ProjectStep(
    String title,
    String detail,
    List<ProjectPart.ProjectSource> uses,
    boolean done,
    Instant doneAt,
    /** What the user wrote while doing it — the part no plan could have known. */
    String note
) {
    public ProjectStep {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        uses = uses == null ? List.of() : List.copyOf(uses);
    }

    public ProjectStep ticked(boolean nowDone, Instant at) {
        return new ProjectStep(title, detail, uses, nowDone, nowDone ? at : null, note);
    }

    public ProjectStep annotated(String newNote) {
        return new ProjectStep(title, detail, uses, done, doneAt, newNote);
    }
}
