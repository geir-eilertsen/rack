package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.SuggestSlot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public class SuggestController {

    private final SuggestSlot suggest;
    private final Batches batches;

    public SuggestController(SuggestSlot suggest, Batches batches) {
        this.suggest = suggest;
        this.batches = batches;
    }

    /** Photo parts or staged ids, as everywhere a batch is taken in. A suggestion files nothing, so the staged copies stay. */
    @PostMapping(value = "/suggest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuggestSlot.Result suggest(@RequestParam(value = "photo", required = false) List<MultipartFile> photos,
                                      @RequestParam(value = "staged", required = false) List<String> staged) throws IOException {
        return suggest.execute(batches.bytes(photos, staged));
    }
}
