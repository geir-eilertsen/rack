package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.model.StagedPhoto;
import family.eilertsen.rack.domain.port.PhotoStaging;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Photographs shot and not yet filed. The page uploads each frame the moment
 * the camera hands it over, as it came, and shows the small copy this
 * returns; filing then names the ids. A page relaunched with the batch gone
 * from memory asks {@code GET /staging} and has it back.
 */
@RestController
@RequestMapping("/staging")
public class StagingController {

    private final PhotoStaging staging;

    public StagingController(PhotoStaging staging) {
        this.staging = staging;
    }

    public record StagedView(String id, String url, String preview, Instant at, String container, String slot) {
        static StagedView of(StagedPhoto s) {
            return new StagedView(s.id(), "/staging/" + s.id(), "/staging/" + s.id() + "?w=264", s.at(),
                s.container() == null ? null : s.container().value(),
                s.slot() == null ? null : s.slot().value());
        }
    }

    /**
     * Repeated {@code photo} parts, full camera frames; {@code c} and {@code s}
     * say where the page was pointed, so a relaunch can go back there.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<StagedView> stage(@RequestParam("photo") List<MultipartFile> photos,
                                  @RequestParam(value = "c", required = false) String container,
                                  @RequestParam(value = "s", required = false) String slot) throws IOException {
        ContainerId cid = blank(container) ? null : new ContainerId(container);
        SlotId sid = blank(slot) ? null : new SlotId(slot);
        List<StagedView> staged = new ArrayList<>(photos.size());
        for (MultipartFile photo : photos) {
            staged.add(StagedView.of(staging.stage(photo.getBytes(), photo.getContentType(), cid, sid)));
        }
        return staged;
    }

    @GetMapping
    public List<StagedView> all() {
        return staging.all().stream().map(StagedView::of).toList();
    }

    /** The photograph, or with {@code ?w=} the small copy for the strip. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable String id,
                                        @RequestParam(name = "w", required = false) Integer width) {
        byte[] bytes = width == null ? staging.read(id) : staging.preview(id);
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            // The bytes never change under an id; only whether they are still there does.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
            .body(bytes);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id) {
        staging.remove(id);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
