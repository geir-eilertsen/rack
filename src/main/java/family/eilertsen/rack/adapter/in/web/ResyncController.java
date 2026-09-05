package family.eilertsen.rack.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.application.ResyncSlot;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * Two posts, not one: the same batch is shot once, previewed, and only sent for
 * real when the user has agreed to the removals. The preview stores nothing, so
 * the frames come up the wire twice — a few hundred kilobytes to avoid holding
 * a half-finished resync in server state.
 */
@RestController
@RequestMapping("/c/{container}/{slot}/resync")
public class ResyncController {

    private final ResyncSlot resync;
    private final ContainerRegistry registry;
    private final ObjectMapper mapper;

    private final Batches batches;

    public ResyncController(ResyncSlot resync, ContainerRegistry registry, ObjectMapper mapper, Batches batches) {
        this.batches = batches;
        this.resync = resync;
        this.registry = registry;
        this.mapper = mapper;
    }

    /** Repeated {@code photo} parts or {@code staged} ids, as everywhere else a batch is shot. */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResyncSlot.Preview preview(@PathVariable String container,
                                       @PathVariable String slot,
                                       @RequestParam(value = "photo", required = false) List<MultipartFile> photos,
                                       @RequestParam(value = "staged", required = false) List<String> staged) throws IOException {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            return resync.preview(cid, sid, batches.bytes(photos, staged));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * The photos again, plus the decisions as a JSON part — a multipart request
     * cannot carry a JSON body alongside the files, so the body rides as a field.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Slot apply(@PathVariable String container,
                       @PathVariable String slot,
                       @RequestParam(value = "photo", required = false) List<MultipartFile> photos,
                       @RequestParam(value = "staged", required = false) List<String> staged,
                       @RequestParam("decisions") String decisions) throws IOException {
        ContainerId cid = new ContainerId(container);
        SlotId sid = new SlotId(slot);
        requireContainerExists(cid);
        try {
            Slot result = resync.apply(cid, sid, batches.bytes(photos, staged), read(decisions));
            batches.release(staged);
            return result;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IndexOutOfBoundsException e) {
            // The slot changed between the preview and the confirmation, so the
            // index names an item that is no longer there.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    private ResyncSlot.Decisions read(String decisions) {
        try {
            ResyncSlot.Decisions parsed = mapper.readValue(decisions, ResyncSlot.Decisions.class);
            // Keeping nothing is a legitimate answer — the drawer is empty —
            // which is exactly why a decisions part that says nothing at all has
            // to be refused rather than read as one.
            if (parsed == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decisions is required");
            return parsed;
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable decisions: " + e.getOriginalMessage(), e);
        }
    }


    private void requireContainerExists(ContainerId id) {
        registry.get(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + id.value()));
    }
}
