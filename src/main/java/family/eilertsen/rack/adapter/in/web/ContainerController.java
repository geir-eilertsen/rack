package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AddPhotoToSlot;
import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/c")
public class ContainerController {

    private final ContainerRegistry registry;
    private final PartIndex index;
    private final AddPhotoToSlot addPhoto;

    public ContainerController(ContainerRegistry registry, PartIndex index, AddPhotoToSlot addPhoto) {
        this.registry = registry;
        this.index = index;
        this.addPhoto = addPhoto;
    }

    @GetMapping
    public Collection<Container> list() {
        return registry.all();
    }

    @GetMapping("/{container}")
    public Container container(@PathVariable String container) {
        return registry.get(new ContainerId(container))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + container));
    }

    @GetMapping("/{container}/{slot}")
    public Slot getSlot(@PathVariable String container, @PathVariable String slot) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        return index.get(cid, sid).orElse(new Slot(sid, List.of(), null, List.of(), null));
    }

    @PostMapping(value = "/{container}/{slot}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddPhotoToSlot.Result addPhoto(@PathVariable String container,
                                           @PathVariable String slot,
                                           @RequestParam("photo") MultipartFile photo) throws IOException {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        return addPhoto.execute(cid, sid, photo.getBytes(), photo.getContentType());
    }

    private void requireContainerExists(ContainerId id) {
        registry.get(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + id.value()));
    }
}
