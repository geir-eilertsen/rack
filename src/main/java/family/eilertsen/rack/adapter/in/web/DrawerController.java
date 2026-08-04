package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AddPhotoToDrawer;
import family.eilertsen.rack.domain.model.Drawer;
import family.eilertsen.rack.domain.model.DrawerId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/drawers")
public class DrawerController {

    private final AddPhotoToDrawer addPhoto;
    private final PartIndex index;

    public DrawerController(AddPhotoToDrawer addPhoto, PartIndex index) {
        this.addPhoto = addPhoto;
        this.index = index;
    }

    @GetMapping("/{id}")
    public Drawer get(@PathVariable String id) {
        DrawerId did = new DrawerId(id);
        return index.get(did)
            .orElse(new Drawer(did, List.of(), null, List.of()));
    }

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddPhotoToDrawer.Result addPhoto(@PathVariable String id, @RequestParam("photo") MultipartFile photo) throws IOException {
        return addPhoto.execute(new DrawerId(id), photo.getBytes(), photo.getContentType());
    }
}
