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
 * <p><strong>A link is a bookmark, not a copy.</strong> rack cannot fetch
 * anything, so a link is the address of a page somebody else keeps: when it goes,
 * it goes, and no sweep or backup here will bring it back. Worth having for the
 * manufacturer's page that is always the newest revision, and worth knowing about
 * before it is the only record of a part.
 *
 * <p>An item's datasheet is the clearest case for keeping one at all: the part
 * number on a chip is three millimetres wide and the pinout is not on it, so the
 * PDF is the difference between a drawer that tells you what is in it and one
 * that tells you what you can do with it.
 */
public record Document(
    /** The stored file, or null when this is a link. */
    String filename,
    /** What it is, in the user's words. Defaults to the name of the file uploaded. */
    String title,
    String contentType,
    long size,
    Instant addedAt,
    /** Somewhere else, or null when this is a stored file. */
    String url
) {
    public Document {
        boolean hasFile = filename != null && !filename.isBlank();
        boolean hasUrl = url != null && !url.isBlank();
        if (hasFile == hasUrl) {
            throw new IllegalArgumentException("a document is either a stored file or a link, not both or neither");
        }
        if (hasUrl) {
            url = url.strip();
            requireWebAddress(url);
        }
        if (title == null || title.isBlank()) title = hasFile ? filename : url;
    }

    /**
     * Only {@code http} and {@code https}.
     *
     * <p>These are rendered straight into an {@code href}, and a
     * {@code javascript:} address in one runs when it is clicked. Escaping the
     * text does not help — the browser reads the scheme, not the markup. So the
     * scheme is checked here, where every path that stores a link has to pass.
     */
    private static void requireWebAddress(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException(
                "A link must start with http:// or https://; got: " + url);
        }
        if (url.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("A link cannot contain control characters");
        }
    }

    public boolean isLink() {
        return url != null && !url.isBlank();
    }

    public Document retitled(String newTitle) {
        return new Document(filename, newTitle, contentType, size, addedAt, url);
    }
}
