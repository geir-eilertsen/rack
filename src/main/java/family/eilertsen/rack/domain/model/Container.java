package family.eilertsen.rack.domain.model;

import java.util.List;

public record Container(
    ContainerId id,
    String name,
    List<SlotId> slots,
    float labelScale
) {}
