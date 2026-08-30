package family.eilertsen.rack.domain.model;

import java.util.Comparator;
import java.util.List;

public record Container(
    ContainerId id,
    String name,
    List<SlotId> slots,
    float labelScale,
    String slotLabel
) {
    public static final float MAX_LABEL_SCALE = 2.0f;

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
