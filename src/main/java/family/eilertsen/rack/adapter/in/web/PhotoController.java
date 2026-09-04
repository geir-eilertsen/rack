package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.port.ImageStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * Photographs are served from one place, not from under the drawer they were
 * taken of — an item that moves keeps its references, and a frame showing three
 * things belongs to all three.
 */
@RestController
public class PhotoController {

    private final ImageStore images;

    public PhotoController(ImageStore images) {
        this.images = images;
    }

    /**
     * Two thumbnail sizes and no more, so the cache on disk holds two files
     * per photograph rather than one per pixel width a page ever asked for:
     * 160 for a 48px thumbnail on a 3× screen, 320 for an 84px strip frame.
     */
    static final int SMALL = 160;
    static final int LARGE = 320;

    /**
     * {@code ?w=} asks for a thumbnail no longer than that on its longer side.
     * A phone decodes every image at the size it was sent, and a drawer of
     * twenty items with full photographs for thumbnails was twenty full
     * photographs in memory — which the camera app needs next.
     */
    @GetMapping("/photos/{filename}")
    public ResponseEntity<byte[]> photo(@PathVariable String filename,
                                        @RequestParam(name = "w", required = false) Integer width) {
        byte[] bytes;
        try {
            bytes = width == null ? images.read(filename) : images.thumbnail(filename, edge(width));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo: " + filename, e);
        }
        return ResponseEntity.ok()
            .contentType(mediaType(filename))
            // The name carries a timestamp and the bytes never change under it,
            // so a phone re-opening a drawer should not fetch them again.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .body(bytes);
    }

    static int edge(int width) {
        return width <= SMALL ? SMALL : LARGE;
    }

    private static MediaType mediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }
}
