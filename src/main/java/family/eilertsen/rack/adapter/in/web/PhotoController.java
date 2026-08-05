package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.port.ImageStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/photos/{filename}")
    public ResponseEntity<byte[]> photo(@PathVariable String filename) {
        byte[] bytes;
        try {
            bytes = images.read(filename);
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

    private static MediaType mediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }
}
