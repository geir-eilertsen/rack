package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
public class LabelSheetController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss");

    private final ContainerRegistry registry;
    private final PartIndex index;
    private final Path dataDir;

    public LabelSheetController(ContainerRegistry registry, PartIndex index,
                                 @Value("${rack.data-dir}") String dataDir) {
        this.registry = registry;
        this.index = index;
        this.dataDir = Path.of(dataDir).toAbsolutePath();
    }

    @GetMapping("/labels/{container}")
    public ResponseEntity<byte[]> preview(HttpServletRequest req,
                                           @PathVariable String container,
                                           @RequestParam(defaultValue = "unprinted") String scope,
                                           @RequestParam(required = false) String base) throws IOException {
        Container c = container(container);
        List<SlotId> slots = pickSlots(c, scope);
        byte[] pdf = LabelSheet.build(base != null ? base : requestBase(req), c, slots);
        return pdfResponse(c, pdf);
    }

    @PostMapping("/labels/{container}")
    public ResponseEntity<byte[]> print(HttpServletRequest req,
                                         @PathVariable String container,
                                         @RequestParam(defaultValue = "unprinted") String scope,
                                         @RequestParam(required = false) String base) throws IOException {
        Container c = container(container);
        List<SlotId> slots = pickSlots(c, scope);
        byte[] pdf = LabelSheet.build(base != null ? base : requestBase(req), c, slots);
        savePdf(c, pdf);
        markPrinted(c, slots);
        return pdfResponse(c, pdf);
    }

    @GetMapping("/labels/{container}/status")
    public LabelStatus status(@PathVariable String container) {
        Container c = container(container);
        int printed = 0;
        int unprinted = 0;
        for (SlotId sid : c.slots()) {
            boolean isPrinted = index.get(c.id(), sid).map(s -> s.printedAt() != null).orElse(false);
            if (isPrinted) printed++; else unprinted++;
        }
        return new LabelStatus(c.id().value(), c.slots().size(), printed, unprinted);
    }

    public record LabelStatus(String container, int total, int printed, int unprinted) {}

    private Container container(String id) {
        return registry.get(new ContainerId(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + id));
    }

    private List<SlotId> pickSlots(Container c, String scope) {
        if ("all".equals(scope)) return c.slots();
        return c.slots().stream()
            .filter(sid -> !index.get(c.id(), sid).map(s -> s.printedAt() != null).orElse(false))
            .toList();
    }

    private void markPrinted(Container c, List<SlotId> slots) {
        Instant now = Instant.now();
        for (SlotId sid : slots) {
            Slot existing = index.get(c.id(), sid).orElse(new Slot(sid, List.of(), null, List.of(), null));
            Slot marked = new Slot(existing.id(), existing.items(), existing.lastVerified(), existing.photos(), now);
            index.save(c.id(), marked);
        }
    }

    private void savePdf(Container c, byte[] pdf) throws IOException {
        Path dir = dataDir.resolve(c.id().value()).resolve("labels");
        Files.createDirectories(dir);
        String filename = LocalDateTime.now().format(STAMP) + ".pdf";
        Files.write(dir.resolve(filename), pdf);
    }

    private ResponseEntity<byte[]> pdfResponse(Container c, byte[] pdf) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "inline; filename=\"" + c.id().value() + "-labels.pdf\"")
            .body(pdf);
    }

    private static String requestBase(HttpServletRequest req) {
        String scheme = req.getScheme();
        int port = req.getServerPort();
        boolean defaultPort = (scheme.equals("http") && port == 80)
            || (scheme.equals("https") && port == 443);
        return scheme + "://" + req.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
