package family.eilertsen.rack.domain.model;

import java.util.List;

public record Container(
    ContainerId id,
    String name,
    List<SlotId> slots,
    float labelScale
) {
    public static final float MAX_LABEL_SCALE = 2.0f;

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
