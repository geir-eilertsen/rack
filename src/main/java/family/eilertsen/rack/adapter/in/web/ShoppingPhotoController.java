package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.ReadForShopping;
import family.eilertsen.rack.domain.model.Framing;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public class ShoppingPhotoController {

    private final ReadForShopping reader;
    private final Batches batches;

    public ShoppingPhotoController(ReadForShopping reader, Batches batches) {
        this.reader = reader;
        this.batches = batches;
    }

    /**
     * What a photo for a shopping line shows, read by the vision model and
     * nothing stored — the page fills in the line and the person adds it.
     * The photo is fitted on arrival, as a drawer's is.
     */
    @PostMapping(value = "/shopping/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReadForShopping.Reading read(@RequestParam("photo") MultipartFile photo,
                                        @RequestParam(value = "seen", required = false) String seen) throws IOException {
        List<byte[]> fitted = batches.bytes(List.of(photo), null, Framing.parse(seen));
        return reader.read(fitted.get(0));
    }
}
