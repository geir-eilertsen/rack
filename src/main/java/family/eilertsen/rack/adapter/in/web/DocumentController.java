package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.port.DocumentStore;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

/**
 * Serves a kept document. Flat, like {@code /photos/{filename}} — the filename is
 * validated as a bare name in the store, because one folder for the whole rack
 * means a {@code ../} would walk out of the data directory.
 */
@RestController
public class DocumentController {

    private final DocumentStore documents;

    public DocumentController(DocumentStore documents) {
        this.documents = documents;
    }

    @GetMapping("/documents/{filename}")
    public ResponseEntity<byte[]> document(@PathVariable String filename) {
        byte[] bytes = documents.read(filename);
        return ResponseEntity.ok()
            // Inline: a service manual is for reading on the bench, not for
            // downloading a second copy of every time you check a resistor value.
            // build() before toString(), or the header reads
            // "org.springframework.http.ContentDisposition$BuilderImpl@1d782749".
            .header("Content-Disposition", ContentDisposition.inline().filename(filename).build().toString())
            .contentType(typeOf(filename))
            // The name is unique per upload, so the bytes behind it never change.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(bytes);
    }

    private static MediaType typeOf(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (name.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (name.endsWith(".txt") || name.endsWith(".md")) return MediaType.TEXT_PLAIN;
        if (name.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
