package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.FindByPhoto;
import family.eilertsen.rack.application.FindItems;
import family.eilertsen.rack.domain.port.SearchHistory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
public class SearchController {

    private final FindItems find;
    private final FindByPhoto findByPhoto;
    private final SearchHistory history;

    public SearchController(FindItems find, FindByPhoto findByPhoto, SearchHistory history) {
        this.find = find;
        this.findByPhoto = findByPhoto;
        this.history = history;
    }

    /**
     * {@code smart=false} (the default) is the literal pass — instant, no model
     * call, safe to fire on every keystroke. The page follows up with
     * {@code smart=true} once typing settles, and that one widens the query when
     * the literal pass came up short.
     */
    @GetMapping("/search")
    public FindItems.Result search(
        @RequestParam String q,
        @RequestParam(defaultValue = "false") boolean smart
    ) {
        return smart ? find.smart(q) : find.literal(q);
    }

    /** The last few things someone went looking for, most recent first. */
    @GetMapping("/searches")
    public List<String> searches() {
        return history.recent();
    }

    /**
     * Remember a query, because someone submitted it rather than because they
     * paused while typing it.
     *
     * <p>This is a POST and not a flag on the search, for two reasons. Searching
     * is a read and should stay one. And timing cannot tell "b" from "batteri" —
     * pause long enough between keystrokes and every prefix looks like a query
     * someone meant, which is exactly what filled the list with b, ba, batt.
     */
    @PostMapping("/searches")
    public List<String> remember(@RequestBody Remembered body) {
        history.remember(body.query());
        return history.recent();
    }

    public record Remembered(String query) {}

    /**
     * Search by holding the part up to the camera. Takes **repeated `photo`
     * parts** like the filing endpoints — one part is just a batch of one — and
     * answers in the same shape as {@code GET /search}, so the page renders a
     * photo search and a typed one with the same code.
     */
    @PostMapping(value = "/search/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FindItems.Result searchByPhoto(@RequestParam("photo") List<MultipartFile> photos) throws IOException {
        List<byte[]> images = new ArrayList<>(photos.size());
        for (MultipartFile photo : photos) images.add(photo.getBytes());
        return findByPhoto.execute(images);
    }
}
