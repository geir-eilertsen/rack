package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * A file kept with something: a service manual on a project, a datasheet on an
 * item, a schematic, the photograph of a board before it was stripped.
 *
 * <p>Both a project and an item can hold these, and both hold them the same way —
 * the owner names the file and the file sits flat in {@code data/documents/}. The
 * same rule photographs follow, for the same reason: the thing that owns a file
 * gets renamed, moved and deleted, and the file should not have to move with it.
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
 * <p>An item's datasheet is the clearest case for keeping one at all: the part
 * number on a chip is three millimetres wide and the pinout is not on it, so the
 * PDF is the difference between a drawer that tells you what is in it and one
 * that tells you what you can do with it.
 */
public record Document(
    String filename,
    /** What it is, in the user's words. Defaults to the name of the file uploaded. */
    String title,
    String contentType,
    long size,
    Instant addedAt
) {
    public Document {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("filename is required");
        if (title == null || title.isBlank()) title = filename;
    }

    public Document retitled(String newTitle) {
        return new Document(filename, newTitle, contentType, size, addedAt);
    }
}
