package family.eilertsen.rack.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record ContainerId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    public ContainerId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Container id must be lowercase alphanumeric/hyphen, 1-32 chars, starting with a letter; got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
