package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.application.RackProperties;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import jakarta.servlet.http.HttpServletRequest;
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
    private final String configuredBase;

    public LabelSheetController(ContainerRegistry registry, PartIndex index, RackProperties props) {
        this.registry = registry;
        this.index = index;
        this.dataDir = Path.of(props.dataDir()).toAbsolutePath();
        this.configuredBase = props.publicBaseUrl();
    }

    @GetMapping("/labels/{container}")
    public ResponseEntity<byte[]> preview(HttpServletRequest req,
                                           @PathVariable String container,
                                           @RequestParam(defaultValue = "unprinted") String scope,
                                           @RequestParam(required = false) Integer offset,
                                           @RequestParam(required = false) String base) throws IOException {
        Container c = container(container);
        List<SlotId> slots = pickSlots(c, scope);
        int firstPageOffset = resolveOffset(c, offset);
        byte[] pdf = LabelSheet.build(resolveBase(base, req), c, slots, firstPageOffset);
        return pdfResponse(c.id().value(), pdf);
    }

    @PostMapping("/labels/{container}")
    public ResponseEntity<byte[]> print(HttpServletRequest req,
                                         @PathVariable String container,
                                         @RequestParam(defaultValue = "unprinted") String scope,
                                         @RequestParam(required = false) Integer offset,
                                         @RequestParam(required = false) String base) throws IOException {
        Container c = container(container);
        List<SlotId> slots = pickSlots(c, scope);
        int firstPageOffset = resolveOffset(c, offset);
        byte[] pdf = LabelSheet.build(resolveBase(base, req), c, slots, firstPageOffset);
        savePdf(c, pdf);
        markPrinted(c, slots, Instant.now());
        return pdfResponse(c.id().value(), pdf);
    }

    private String resolveBase(String explicit, HttpServletRequest req) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (configuredBase != null && !configuredBase.isBlank()) return configuredBase;
        return requestBase(req);
    }

    @GetMapping("/labels/{container}/status")
    public LabelStatus status(@PathVariable String container) {
        return statusOf(container(container));
    }

    private LabelStatus statusOf(Container c) {
        int printed = printedCount(c);
        int unprinted = c.slots().size() - printed;
        int positions = LabelSheet.positionCount(pickSlots(c, "unprinted").stream()
            .map(sid -> new LabelSheet.Label(c, sid))
            .toList());
        return new LabelStatus(c.id().value(), c.slots().size(), printed, unprinted,
            resolveOffset(c, null), LabelSheet.PER_SHEET, positions);
    }

    /**
     * {@code positions} is how many physical stickers the pending labels need — lower than {@code unprinted}
     * when a small label scale lets several share one. {@code nextOffset} is the shared sheet position.
     */
    public record LabelStatus(String container, int total, int printed, int unprinted,
                              int nextOffset, int perSheet, int positions) {}

    /**
     * A sheet of Avery L7160 paper is a shared physical resource, so the position is counted across every
     * container: if previous runs consumed 4 stickers, the next container's labels start at position 5.
     * A container's own run still packs only its own labels, so a part-filled sticker is never continued
     * by the next container — sharing happens at sticker granularity, not within one.
     */
    private int resolveOffset(Container c, Integer explicit) {
        if (explicit != null) return Math.max(0, explicit % LabelSheet.PER_SHEET);
        return positionsPrintedEverywhere() % LabelSheet.PER_SHEET;
    }

    private int positionsPrintedEverywhere() {
        return registry.all().stream().mapToInt(this::positionsPrinted).sum();
    }

    /**
     * Physical L7160 positions this container's already-printed labels consumed. Packing means printed labels
     * and consumed stickers are not the same number, so they are re-packed to find the real count.
     */
    private int positionsPrinted(Container c) {
        return LabelSheet.positionCount(pickSlots(c, "printed").stream()
            .map(sid -> new LabelSheet.Label(c, sid))
            .toList());
    }

    private int printedCount(Container c) {
        int n = 0;
        for (SlotId sid : c.slots()) {
            if (index.get(c.id(), sid).map(s -> s.printedAt() != null).orElse(false)) n++;
        }
        return n;
    }

    private Container container(String id) {
        return registry.get(new ContainerId(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown container: " + id));
    }

    private List<SlotId> pickSlots(Container c, String scope) {
        if ("all".equals(scope)) return c.slots();
        boolean wantPrinted = "printed".equals(scope);
        return c.slots().stream()
            .filter(sid -> index.get(c.id(), sid).map(s -> s.printedAt() != null).orElse(false) == wantPrinted)
            .toList();
    }

    private void markPrinted(Container c, List<SlotId> slots, Instant now) {
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

    private ResponseEntity<byte[]> pdfResponse(String stem, byte[] pdf) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "inline; filename=\"" + stem + "-labels.pdf\"")
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
