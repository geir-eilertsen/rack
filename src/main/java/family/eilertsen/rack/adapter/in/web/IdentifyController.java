package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public class IdentifyController {

    private final PartExtractor extractor;

    public IdentifyController(PartExtractor extractor) {
        this.extractor = extractor;
    }

    @PostMapping(value = "/identify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Item> identify(@RequestParam("photo") MultipartFile photo) throws IOException {
        return extractor.extract(photo.getBytes());
    }
}
