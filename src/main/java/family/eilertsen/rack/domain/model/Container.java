package family.eilertsen.rack.domain.model;

import java.util.List;

public record Container(
    ContainerId id,
    String name,
    List<SlotId> slots,
    float labelScale,
    String slotLabel
) {
    public static final float MAX_LABEL_SCALE = 2.0f;

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
