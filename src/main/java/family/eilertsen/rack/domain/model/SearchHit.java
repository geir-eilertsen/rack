package family.eilertsen.rack.domain.model;

public record SearchHit(ContainerId container, SlotId slot, Item item, double score) {}
