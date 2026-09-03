package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.SlotId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RegisterContainer {

    private final ContainerRegistry registry;

    public RegisterContainer(ContainerRegistry registry) {
        this.registry = registry;
    }

    public Container execute(Request req) {
        ContainerId id = new ContainerId(req.id());
        String name = req.name() == null || req.name().isBlank() ? id.value() : req.name().trim();
        List<SlotId> slots = expand(req.layout());
        Container c = new Container(id, name, slots, Container.validLabelScale(req.labelScale()),
            Container.validSlotLabel(req.slotLabel()),
            Container.validLocation(req.location()), Container.validNotes(req.notes()));
        registry.add(c);
        return c;
    }

    private static List<SlotId> expand(LayoutSpec spec) {
        if (spec == null || spec.kind() == null) {
            throw new IllegalArgumentException("layout.kind is required (grid, linear or sections)");
        }
        // Every kind ends in the same place: a list of blocks concatenated into the
        // flat slot list a container holds. A grid is one block; a cabinet with
        // bands of different drawer sizes is several.
        return ContainerLayout.sections(blocks(spec));
    }

    private static List<List<SlotId>> blocks(LayoutSpec spec) {
        if (!"sections".equals(spec.kind())) return List.of(block(spec, 1));

        List<LayoutSpec> parts = spec.sections();
        require(parts != null && !parts.isEmpty(), "layout.sections required for sections");
        List<List<SlotId>> blocks = new ArrayList<>(parts.size());
        // Bands carry the row numbering on rather than each starting at 1: that is
        // what keeps the ids unique without anybody being asked to pick column
        // letters, and what lets a band one row deep be told apart from the row
        // above it when the shape is read back off the ids.
        int firstRow = 1;
        for (LayoutSpec part : parts) {
            require(part != null && part.kind() != null, "each section needs a kind (grid or linear)");
            require(part.sections() == null, "a section cannot itself hold sections");
            blocks.add(block(part, firstRow));
            if ("grid".equals(part.kind())) firstRow += part.rows();
        }
        return blocks;
    }

    /** Expands one block, its rows numbered from {@code firstRow}. */
    private static List<SlotId> block(LayoutSpec spec, int firstRow) {
        return switch (spec.kind()) {
            case "grid" -> {
                require(spec.cols() != null && spec.cols() > 0, "layout.cols required for grid");
                require(spec.rows() != null && spec.rows() > 0, "layout.rows required for grid");
                String letters = spec.letters() == null || spec.letters().isBlank()
                    ? ContainerLayout.COLUMN_LETTERS
                    : spec.letters().trim();
                yield ContainerLayout.grid(spec.cols(), spec.rows(), letters, firstRow);
            }
            case "linear" -> {
                require(spec.count() != null && spec.count() > 0, "layout.count required for linear");
                yield ContainerLayout.linear(spec.count(), spec.prefix());
            }
            default -> throw new IllegalArgumentException("Unknown layout kind: " + spec.kind());
        };
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new IllegalArgumentException(message);
    }

    public record Request(String id, String name, LayoutSpec layout, Float labelScale, String slotLabel,
                          String location, String notes) {}

    /**
     * One layout, or — for a {@code sections} kind — a list of them. A section is
     * itself a grid or a linear run, so the shape a cabinet of mixed drawer sizes has
     * is spelled as the bands it is made of; {@code letters} names a grid's columns
     * where A, B, C is not what you want them called.
     */
    public record LayoutSpec(String kind, Integer cols, Integer rows, Integer count, String prefix,
                             String letters, List<LayoutSpec> sections) {}
}
