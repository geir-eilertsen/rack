package family.eilertsen.rack.domain.model;

/**
 * One entry the model related to a subject: which subject (zero-based, in the
 * order the subjects were given), the reference of a line in the listing it
 * was shown, how the two relate, and what the one does for the other. Nothing
 * here is believed until the reference resolves to an item the index actually
 * holds.
 */
public record Companion(int subject, String ref, Kind kind, String why) {

    /**
     * A pair is the other half — a charger and its device. The same kind is
     * where the subject would be filed alongside — another microSD card,
     * whatever the brand or capacity — which is a drawer suggestion, not a
     * move.
     */
    public enum Kind { PAIR, SAME_KIND }
}
