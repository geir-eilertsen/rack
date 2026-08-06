package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A job of work, from finding out what it needs to putting the lid back on.
 *
 * <p><strong>Why this is a stored thing and not a page of advice.</strong> Asking
 * "do I have all the parts" and getting a plan is one moment; a restoration is
 * weeks. Parts arrive on different days from different suppliers, steps get done
 * out of order, and the answer to "where was I" is worth more than the answer to
 * "what does it need" — you only need the second one once.
 *
 * <p>It also closes the last hole in the drift argument. A project is how stock
 * <em>leaves</em> the rack: eight of ten emitter resistors go into an amplifier
 * and the drawer still claims ten. Nothing else in the app models consumption, so
 * nothing else could ever have caught it. {@code parts} therefore records what
 * each line took from which drawer, which is what lets finishing a project
 * settle up rather than guess.
 *
 * <p>Everything the model produced is kept as the user's to edit. A step's
 * wording, a part's quantity, the order it all happens in — none of that is
 * regenerated, because a plan you have annotated is worth more than a fresh one.
 */
public record Project(
    ProjectId id,
    String name,
    /** What was asked, kept verbatim: the plan is only intelligible beside it. */
    String brief,
    String status,
    Instant startedAt,
    Instant updatedAt,
    Instant finishedAt,
    List<ProjectPart> parts,
    List<ProjectStep> steps,
    List<String> cautions,
    /** What happened, oldest first. A project's memory of itself. */
    List<ProjectNote> log,
    /** Service manuals, schematics, photographs of the board before it was stripped. */
    List<Document> documents
) {
    public static final String PLANNING = "planning";
    public static final String SHOPPING = "shopping";
    public static final String BUILDING = "building";
    public static final String DONE = "done";
    public static final String PARKED = "parked";

    private static final List<String> STATUSES = List.of(PLANNING, SHOPPING, BUILDING, DONE, PARKED);

    public Project {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        status = status == null || status.isBlank() ? PLANNING : status.strip();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unknown project status: " + status + " (expected one of " + STATUSES + ")");
        }
        parts = parts == null ? List.of() : List.copyOf(parts);
        steps = steps == null ? List.of() : List.copyOf(steps);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
        log = log == null ? List.of() : List.copyOf(log);
        // Absent from every project file written before documents existed. No
        // migration, per the storage model: a missing field reads as none.
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public static boolean isStatus(String s) {
        return s != null && STATUSES.contains(s.strip());
    }

    public static List<String> statuses() {
        return STATUSES;
    }

    /** Open in the sense that matters: it still wants something doing. */
    public boolean open() {
        return !DONE.equals(status) && !PARKED.equals(status);
    }

    public int stepsDone() {
        return (int) steps.stream().filter(ProjectStep::done).count();
    }

    public int stillToBuy() {
        return (int) parts.stream().filter(ProjectPart::outstanding).count();
    }

    /**
     * The next thing to do, or null when the steps are all ticked.
     *
     * <p>First undone step rather than a cleverer choice: the plan is already in
     * the order the job goes, and a project's most-asked question is "where was I".
     */
    public ProjectStep next() {
        return steps.stream().filter(s -> !s.done()).findFirst().orElse(null);
    }
}
