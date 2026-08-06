package family.eilertsen.rack.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * One line of what a project needs, and where it stands.
 *
 * <p>{@code from} is the drawers it comes out of, for the part of the list
 * already in stock. {@code usedQty} is what the job actually took, which is the
 * number that settles up against the index at the end — separate from
 * {@code qty}, because what a plan estimates and what a job consumes are
 * different facts and the second one is only known afterwards.
 */
public record ProjectPart(
    String part,
    String qty,
    String status,
    String supplier,
    String search,
    String code,
    String note,
    List<ProjectSource> from,
    Integer usedQty
) {
    /** Already on the shelf when the project started. */
    public static final String IN_STOCK = "in_stock";
    public static final String TO_BUY = "to_buy";
    public static final String ORDERED = "ordered";
    public static final String ARRIVED = "arrived";
    /** Fitted, or otherwise gone into the job. */
    public static final String USED = "used";

    private static final List<String> STATUSES = List.of(IN_STOCK, TO_BUY, ORDERED, ARRIVED, USED);

    public ProjectPart {
        if (part == null || part.isBlank()) throw new IllegalArgumentException("part is required");
        status = status == null || status.isBlank() ? TO_BUY : status.strip();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unknown part status: " + status + " (expected one of " + STATUSES + ")");
        }
        from = from == null ? List.of() : List.copyOf(from);
    }

    public static boolean isStatus(String s) {
        return s != null && STATUSES.contains(s.strip());
    }

    public static List<String> statuses() {
        return STATUSES;
    }

    /** Still needs buying or is on its way. */
    public boolean outstanding() {
        return TO_BUY.equals(status) || ORDERED.equals(status);
    }

    public ProjectPart withStatus(String newStatus, Integer used) {
        return new ProjectPart(part, qty, newStatus, supplier, search, code, note, from,
            used != null ? used : usedQty);
    }

    /** Where a part already in stock is sitting. */
    public record ProjectSource(ContainerId container, SlotId slot, String item) {
        public ProjectSource {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(slot, "slot");
        }
    }
}
