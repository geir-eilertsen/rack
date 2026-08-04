package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.SuggestSlot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class SuggestController {

    private final SuggestSlot suggest;

    public SuggestController(SuggestSlot suggest) {
        this.suggest = suggest;
    }

    @PostMapping(value = "/suggest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuggestSlot.Result suggest(@RequestParam("photo") MultipartFile photo) throws IOException {
        return suggest.execute(photo.getBytes());
    }
}
