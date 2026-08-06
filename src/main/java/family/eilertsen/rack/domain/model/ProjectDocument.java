package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * A file kept with a project: the service manual, a schematic, the photograph of
 * a board before it was stripped.
 *
 * <p><strong>Stored, not linked.</strong> The app has no way to reach the
 * internet, and the model that writes the plans knows it — asked where to buy
 * heat sink compound it answered "I can't browse the internet, but here are
 * reliable sources". A list of URLs from a model that cannot open one is a list
 * of guesses, and the one thing worse than not having the manual is being sent
 * somewhere it is not. So rack holds the file you found, and finding it stays
 * your errand.
 *
 * <p>Which is the more useful half anyway: step one of the Quad 606 plan is
 * "download the service manual", and the point of keeping it is that step one
 * never has to happen twice.
 *
 * <p>{@code filename} is the stored name, flat in {@code data/documents/} the way
 * photographs are. The project is the only thing that points at it.
 */
public record ProjectDocument(
    String filename,
    /** What it is, in the user's words. Defaults to the name of the file uploaded. */
    String title,
    String contentType,
    long size,
    Instant addedAt
) {
    public ProjectDocument {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("filename is required");
        if (title == null || title.isBlank()) title = filename;
    }

    public ProjectDocument retitled(String newTitle) {
        return new ProjectDocument(filename, newTitle, contentType, size, addedAt);
    }
}
