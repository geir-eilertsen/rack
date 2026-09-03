package family.eilertsen.rack.domain.model;

import java.util.Comparator;
import java.util.List;

public record Container(
    ContainerId id,
    String name,
    List<SlotId> slots,
    float labelScale,
    String slotLabel,
    /**
     * Where the thing physically is — "garage, north wall", "under the stairs".
     * Free text rather than a room chosen from a list: a list of rooms is one more
     * thing that has to be kept true, and the half of an answer that does the work
     * ("north wall") is the half no list has a field for. The index says which
     * drawer; this is the only thing that says which room to walk to. Absent from
     * every container registered before it existed, which reads as unknown.
     */
    String location,
    /**
     * Anything else worth knowing about the container itself — what it is for, what
     * its slot ids mean, which of two identical boxes this is. About the box, not
     * about what is in it: an item's own text is where a part is described.
     */
    String notes
) {
    public static final float MAX_LABEL_SCALE = 2.0f;

    /** A place is a phrase, not a paragraph — it sets on one line of a card. */
    public static final int MAX_LOCATION = 120;

    /** A note about a box. Generous, and a bound rather than a budget. */
    public static final int MAX_NOTES = 2000;

    /**
     * The order a person reads a list of containers in: by the name on the front,
     * ignoring case. Registration order is the order they happened to be created
     * in, which is a fact about the past rather than about the shelf, and it left
     * "Vaskerom" between two plastic boxes because that is the week it was added.
     *
     * <p>A run of digits compares as a number, so "Plastboks 2" comes before
     * "Plastboks 10" where character order puts 10 first — this house numbers its
     * boxes, and the tenth of anything is where that shows. Ties break on the id,
     * which is unique, so the order is total and the same after every restart.
     */
    public static final Comparator<Container> BY_NAME =
        Comparator.comparing(Container::label, Container::natural)
            .thenComparing(c -> c.id().value());

    public Container {
        slotLabel = validSlotLabel(slotLabel);
        // Normalised but not length-checked on the way in. A stored value that is
        // somehow too long should render badly, not stop every container loading:
        // containers.json is one file, so a throw here is the whole shelf at once.
        location = trimmedOrNull(location);
        notes = trimmedOrNull(notes);
    }

    /**
     * What this container's owner calls one of its subdivisions — "drawer",
     * "compartment", "shelf", "bay". The code says slot because that stays true
     * of a rack, a plastic box and a shelf alike; the screen should say what the
     * person standing in front of the thing would say, and only they know.
     */
    public static final String DEFAULT_SLOT_LABEL = "slot";

    /** Defaults an absent or blank label, so a container stored before the field still loads. */
    public static String validSlotLabel(String value) {
        if (value == null || value.isBlank()) return DEFAULT_SLOT_LABEL;
        String label = value.strip();
        if (label.length() > 24) {
            throw new IllegalArgumentException("slotLabel is a word, not a sentence: " + label);
        }
        return label;
    }

    /** The name on the front, falling back to the id for a container registered without one. */
    private static String label(Container c) {
        return c.name() == null || c.name().isBlank() ? c.id().value() : c.name();
    }

    /**
     * Compares two labels the way a shelf is read: case-insensitively, and with a
     * run of digits taken as the number it is rather than character by character.
     */
    private static int natural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int cmp = compareNumbers(a.substring(startA, i), b.substring(startB, j));
                if (cmp != 0) return cmp;
                continue;
            }
            int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (cmp != 0) return cmp;
            i++;
            j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Digit runs, compared as numbers without parsing them — "Box 99999999999999999999" is a name, not an int. */
    private static int compareNumbers(String a, String b) {
        String x = a.replaceFirst("^0+(?=.)", "");
        String y = b.replaceFirst("^0+(?=.)", "");
        return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
    }

    /**
     * Validates a location coming in from outside. Blank clears it, which is how the
     * edit panel says "I do not know where this is any more" — unlike a name, which a
     * container must have. Length is checked here rather than in the constructor, for
     * the same reason {@link #validLabelScale} is.
     */
    public static String validLocation(String value) {
        return checked(value, MAX_LOCATION, "location");
    }

    /** As {@link #validLocation}, for the longer free text. Blank clears it. */
    public static String validNotes(String value) {
        return checked(value, MAX_NOTES, "notes");
    }

    private static String checked(String value, int max, String field) {
        String text = trimmedOrNull(value);
        if (text != null && text.length() > max) {
            throw new IllegalArgumentException(field + " is longer than " + max + " characters");
        }
        return text;
    }

    /** Blank and absent are the same statement about a field nobody has filled in. */
    private static String trimmedOrNull(String value) {
        if (value == null) return null;
        String text = value.strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Validates a label scale coming in from outside, defaulting null to 1.0. Checked here rather than in the
     * constructor so a stored container that predates the field still loads instead of breaking startup.
     */
    public static float validLabelScale(Float value) {
        float scale = value == null ? 1.0f : value;
        if (scale <= 0 || scale > MAX_LABEL_SCALE) {
            throw new IllegalArgumentException("labelScale must be > 0 and <= " + MAX_LABEL_SCALE + ", got " + scale);
        }
        return scale;
    }
}
