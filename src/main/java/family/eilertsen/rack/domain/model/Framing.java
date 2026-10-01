package family.eilertsen.rack.domain.model;

/**
 * How the viewfinder was held when a still was taken: what was on the screen,
 * taller than wide or wider than tall.
 *
 * <p>A still from a phone's camera pipeline is the sensor's frame, which is
 * landscape, with a tag saying which way the phone was held. Some Android
 * builds hand the frame over without the tag, and then nothing in the file
 * says which way up it was — but the page knows, because it was showing the
 * stream the still was taken from. So the page sends this beside the still,
 * and a landscape frame with no word of its own about orientation, taken
 * while the screen showed portrait, is turned the way the screen was.
 * A frame that carries its own tag is believed over this.
 */
public enum Framing {
    PORTRAIT, LANDSCAPE;

    /** {@code "portrait"} or {@code "landscape"}, case-insensitively; anything else is no hint. */
    public static Framing parse(String text) {
        if (text == null) return null;
        return switch (text.trim().toLowerCase()) {
            case "portrait" -> PORTRAIT;
            case "landscape" -> LANDSCAPE;
            default -> null;
        };
    }
}
