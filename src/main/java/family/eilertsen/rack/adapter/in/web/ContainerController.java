package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AddPhotoToSlot;
import family.eilertsen.rack.application.AdjustItemQty;
import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.application.RegisterContainer;
import family.eilertsen.rack.application.RemoveItem;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/c")
public class ContainerController {

    private final ContainerRegistry registry;
    private final PartIndex index;
    private final AddPhotoToSlot addPhoto;
    private final RegisterContainer registerContainer;
    private final RemoveItem removeItem;
    private final AdjustItemQty adjustItemQty;

    public ContainerController(ContainerRegistry registry, PartIndex index, AddPhotoToSlot addPhoto,
                                RegisterContainer registerContainer, RemoveItem removeItem,
                                AdjustItemQty adjustItemQty) {
        this.registry = registry;
        this.index = index;
        this.addPhoto = addPhoto;
        this.registerContainer = registerContainer;
        this.removeItem = removeItem;
        this.adjustItemQty = adjustItemQty;
    }

    @GetMapping
    public Collection<Container> list() {
        return registry.all();
    }

    @PostMapping
    public ResponseEntity<Container> register(@RequestBody RegisterContainer.Request req) {
        try {
            Container c = registerContainer.execute(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(c);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
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

    @DeleteMapping("/{container}/{slot}/items/{index}")
    public Slot removeItem(@PathVariable String container,
                            @PathVariable String slot,
                            @PathVariable int index) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            return removeItem.execute(cid, sid, index);
        } catch (IndexOutOfBoundsException | java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/{container}/{slot}/items/{index}/decrement")
    public Slot decrementItem(@PathVariable String container,
                               @PathVariable String slot,
                               @PathVariable int index) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            return adjustItemQty.decrement(cid, sid, index);
        } catch (IndexOutOfBoundsException | java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/{container}/{slot}/items/{index}/increment")
    public Slot incrementItem(@PathVariable String container,
                               @PathVariable String slot,
                               @PathVariable int index) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            return adjustItemQty.increment(cid, sid, index);
        } catch (IndexOutOfBoundsException | java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    private void requireContainerExists(ContainerId id) {
        registry.get(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + id.value()));
    }
}
