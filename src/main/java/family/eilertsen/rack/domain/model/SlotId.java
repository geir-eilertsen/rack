package family.eilertsen.rack.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record SlotId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,15}$");

    public SlotId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Slot id must be 1-16 chars, alphanumeric or hyphen, starting alphanumeric; got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
