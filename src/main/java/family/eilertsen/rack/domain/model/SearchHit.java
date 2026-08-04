package family.eilertsen.rack.domain.model;

import java.time.Instant;

public record SearchHit(
    ContainerId container,
    SlotId slot,
    Item item,
    double score,
    Instant lastVerified
) {}
