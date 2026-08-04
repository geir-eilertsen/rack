package family.eilertsen.rack.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record DrawerId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[A-E](?:[1-9]|1[0-2])$");

    public DrawerId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Drawer id must match A1–E12, got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
