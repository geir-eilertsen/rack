package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

public record Item(
    String description,
    String partNumber,
    String category,
    Integer qtyEstimate,
    double confidence,
    List<String> tags,
    float[] embedding,
    List<QA> qa
) {
    public record QA(String question, String answer, Instant at) {}
}
