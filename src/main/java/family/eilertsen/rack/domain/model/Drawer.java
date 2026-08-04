package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

public record Drawer(
    DrawerId id,
    List<Item> items,
    Instant lastVerified,
    List<String> photos
) {}
