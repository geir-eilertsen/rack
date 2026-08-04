package family.eilertsen.rack.domain.model;

import java.util.List;

public record Item(
    String description,
    String partNumber,
    String category,
    Integer qtyEstimate,
    double confidence,
    List<String> tags,
    float[] embedding
) {}
