package family.eilertsen.rack.domain.model;

import java.time.Instant;

public record SearchHit(
    ContainerId container,
    SlotId slot,
    int index,
    Item item,
    double score,
    Instant lastVerified
) {}
