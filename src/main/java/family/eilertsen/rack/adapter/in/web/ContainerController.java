package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AddPhotoToSlot;
import family.eilertsen.rack.application.AskAboutItem;
import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.application.DeleteContainer;
import family.eilertsen.rack.application.EditItem;
import family.eilertsen.rack.application.MoveItem;
import family.eilertsen.rack.application.RegisterContainer;
import family.eilertsen.rack.application.RemoveItem;
import family.eilertsen.rack.application.UpdateContainer;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final UpdateContainer updateContainer;
    private final DeleteContainer deleteContainer;
    private final RemoveItem removeItem;
    private final EditItem editItem;
    private final MoveItem moveItem;
    private final AskAboutItem askAboutItem;
    private final ImageStore images;

    public ContainerController(ContainerRegistry registry, PartIndex index, AddPhotoToSlot addPhoto,
                                RegisterContainer registerContainer, UpdateContainer updateContainer,
                                DeleteContainer deleteContainer, RemoveItem removeItem,
                                EditItem editItem, MoveItem moveItem, AskAboutItem askAboutItem, ImageStore images) {
        this.registry = registry;
        this.index = index;
        this.addPhoto = addPhoto;
        this.registerContainer = registerContainer;
        this.updateContainer = updateContainer;
        this.deleteContainer = deleteContainer;
        this.removeItem = removeItem;
        this.editItem = editItem;
        this.moveItem = moveItem;
        this.askAboutItem = askAboutItem;
        this.images = images;
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

    @PatchMapping("/{container}")
    public Container update(@PathVariable String container, @RequestBody UpdateContainer.Fields fields) {
        try {
            return updateContainer.execute(new ContainerId(container), fields);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping("/{container}")
    public Container delete(@PathVariable String container) {
        try {
            return deleteContainer.execute(new ContainerId(container));
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @GetMapping("/{container}/summary")
    public List<SlotSummary> summary(@PathVariable String container) {
        ContainerId cid = new ContainerId(container);
        Container c = registry.get(cid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + container));
        java.util.Map<SlotId, Slot> byId = new java.util.HashMap<>();
        for (Slot s : index.all(cid)) byId.put(s.id(), s);
        return c.slots().stream()
            .map(sid -> {
                Slot s = byId.get(sid);
                if (s == null) return new SlotSummary(sid.value(), 0, 0, false, null);
                return new SlotSummary(
                    sid.value(),
                    s.items() == null ? 0 : s.items().size(),
                    s.photos() == null ? 0 : s.photos().size(),
                    s.printedAt() != null,
                    s.lastVerified()
                );
            })
            .toList();
    }

    public record SlotSummary(String slot, int itemCount, int photoCount, boolean printed, java.time.Instant lastVerified) {}

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

    @PatchMapping("/{container}/{slot}/items/{index}")
    public Slot editItem(@PathVariable String container,
                          @PathVariable String slot,
                          @PathVariable int index,
                          @RequestBody EditItem.Fields fields) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            return editItem.execute(cid, sid, index, fields);
        } catch (IndexOutOfBoundsException | java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/{container}/{slot}/items/{index}/move")
    public Slot moveItem(@PathVariable String container,
                          @PathVariable String slot,
                          @PathVariable int index,
                          @RequestBody MoveRequest req) {
        ContainerId srcC = new ContainerId(container);
        SlotId srcS = new SlotId(slot);
        requireContainerExists(srcC);
        ContainerId dstC = new ContainerId(req.targetContainer());
        SlotId dstS = new SlotId(req.targetSlot());
        try {
            return moveItem.execute(srcC, srcS, index, dstC, dstS);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IndexOutOfBoundsException | java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    public record MoveRequest(String targetContainer, String targetSlot) {}

    @PostMapping("/{container}/{slot}/items/{index}/ask")
    public Slot askItem(@PathVariable String container,
                         @PathVariable String slot,
                         @PathVariable int index,
                         @RequestBody AskRequest req) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            return askAboutItem.execute(cid, sid, index, req.question());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IndexOutOfBoundsException | java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    public record AskRequest(String question) {}

    @GetMapping("/{container}/{slot}/photos/{filename}")
    public ResponseEntity<byte[]> getPhoto(@PathVariable String container,
                                            @PathVariable String slot,
                                            @PathVariable String filename) {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);

        Slot s = index.get(cid, sid).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No slot state: " + container + "/" + slot));
        if (!s.photos().contains(filename)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo: " + filename);
        }

        byte[] bytes = images.read(cid, sid, filename);
        MediaType type = filename.toLowerCase().endsWith(".png")
            ? MediaType.IMAGE_PNG
            : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(bytes);
    }

    private void requireContainerExists(ContainerId id) {
        registry.get(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + id.value()));
    }
}
