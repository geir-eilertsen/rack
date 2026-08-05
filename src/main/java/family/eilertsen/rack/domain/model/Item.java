package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * {@code name} is the short label a human scans a list by ("BC547 transistor");
 * {@code description} is the fuller text. Items extracted before the split have
 * no name — readers fall back to the description rather than migrating.
 */
public record Item(
    String name,
    String description,
    String partNumber,
    String category,
    Integer qtyEstimate,
    double confidence,
    List<String> tags,
    float[] embedding,
    List<QA> qa,
    String sourcePhoto
) {
    public record QA(String question, String answer, Instant at) {}
}
