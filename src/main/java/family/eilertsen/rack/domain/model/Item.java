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
    /**
     * Who made it, as printed on the thing or its packaging — "Anslut",
     * "Wago", "Panasonic". Null when nothing legible says. Kept apart from the
     * description because it is the one word that says which shop sells
     * another: a house brand is a store. Absent from every item filed before
     * this, and nothing back-fills it; the shopping list still reads the old
     * items' text for a brand.
     */
    String brand,
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
        brand = brand == null || brand.isBlank() ? null : brand.strip();
    }

    /** An item with no brand recorded — what everything was before there was one. */
    public Item(String name, String description, String partNumber, String category, Integer qtyEstimate,
                double confidence, List<String> tags, List<QA> qa, String sourcePhoto, List<String> seenIn,
                List<Document> documents) {
        this(name, description, partNumber, null, category, qtyEstimate, confidence, tags, qa, sourcePhoto,
            seenIn, documents);
    }

    public record QA(String question, String answer, Instant at) {}
}
