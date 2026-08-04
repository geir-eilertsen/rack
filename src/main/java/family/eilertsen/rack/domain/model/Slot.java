package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

public record Slot(
    SlotId id,
    List<Item> items,
    Instant lastVerified,
    List<String> photos,
    Instant printedAt
) {}
