package family.eilertsen.rack.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * {@code name} is the short label a human scans a list by ("BC547 transistor");
 * {@code description} is the fuller text. Items extracted before the split have
 * no name — readers fall back to the description rather than migrating.
 *
 * <p>{@code sourcePhoto} is the frame this was read from and {@code seenIn}
 * every frame that shows it, the source first. Items catalogued before the
 * model was asked for the full set have no {@code seenIn} — readers fall back
 * to the slot's own photos, which is what they showed before.
 */
public record Item(
    String name,
    String description,
    String partNumber,
    String category,
    Integer qtyEstimate,
    double confidence,
    List<String> tags,
    List<QA> qa,
    String sourcePhoto,
    List<String> seenIn,
    /**
     * Datasheets and the like. The item owns them, exactly as it owns its
     * photographs — move the item and its datasheet goes with it, delete the item
     * and the file is unreferenced. Absent from every item filed before this,
     * which reads as none.
     */
    List<Document> documents
) {
    public Item {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public record QA(String question, String answer, Instant at) {}
}
