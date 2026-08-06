package family.eilertsen.rack.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Same shape as a {@link ContainerId}, because a project is the same kind of
 * thing: something the user names, that gets a folder, a page and a URL.
 */
public record ProjectId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,47}$");

    public ProjectId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Project id must be lowercase alphanumeric/hyphen, 1-48 chars, starting with a letter; got: " + value);
        }
    }

    /**
     * A usable id from whatever the project was called.
     *
     * <p>"I am restoring a Quad 606 amplifier" becomes {@code i-am-restoring-a-quad-606}
     * — long, but a name nobody typed should not need typing, and it is only ever
     * read in a URL. The caller makes it unique; this only makes it legal.
     */
    public static ProjectId from(String name) {
        String slug = Objects.requireNonNull(name, "name")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "project";
        if (!Character.isLetter(slug.charAt(0))) slug = "p-" + slug;
        return new ProjectId(slug.length() <= 48 ? slug : trimAtWord(slug));
    }

    /** Cut back to a hyphen rather than through a word, so the id still reads. */
    private static String trimAtWord(String slug) {
        String cut = slug.substring(0, 48);
        int lastHyphen = cut.lastIndexOf('-');
        String trimmed = lastHyphen > 0 ? cut.substring(0, lastHyphen) : cut;
        return trimmed.replaceAll("-+$", "");
    }

    @Override
    public String toString() {
        return value;
    }
}
