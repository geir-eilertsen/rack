package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

public record SearchHit(
    ContainerId container,
    SlotId slot,
    int index,
    Item item,
    double score,
    Instant lastVerified,
    List<String> photos
) {}
