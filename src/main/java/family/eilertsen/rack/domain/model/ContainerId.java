package family.eilertsen.rack.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record ContainerId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    /**
     * Names the data directory has already taken.
     *
     * <p>A container is a directory directly under {@code data/}, and so are the
     * app's own {@code photos/} and {@code projects/}. Adding the second of those
     * took the whole app down on the next boot: the index walks every directory
     * under {@code data/}, read a project as a drawer, and refused to start
     * because "i-am-restoring-a-quad-606-amplifier" is not a slot id. Reserving
     * the names here fixes both halves at once — the loader already skips a
     * directory whose name is not a legal container, and registering a container
     * called "projects" now fails at the point of asking rather than colliding
     * silently later. It also retires a long-standing oddity: {@code photos/} has
     * always been loaded as an empty container nobody noticed.
     */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("photos", "projects", "labels");

    public ContainerId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Container id must be lowercase alphanumeric/hyphen, 1-32 chars, starting with a letter; got: " + value);
        }
        if (RESERVED.contains(value)) {
            throw new IllegalArgumentException(
                "\"" + value + "\" is used by the data directory itself; pick another container id");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
