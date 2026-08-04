package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.SlotId;
import org.springframework.stereotype.Service;

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
        float scale = req.labelScale() == null ? 1.0f : req.labelScale();
        if (scale <= 0 || scale > 2) {
            throw new IllegalArgumentException("labelScale must be > 0 and <= 2, got " + scale);
        }
        Container c = new Container(id, name, slots, scale);
        registry.add(c);
        return c;
    }

    private static List<SlotId> expand(LayoutSpec spec) {
        if (spec == null || spec.kind() == null) {
            throw new IllegalArgumentException("layout.kind is required (grid or linear)");
        }
        return switch (spec.kind()) {
            case "grid" -> {
                require(spec.cols() != null && spec.cols() > 0, "layout.cols required for grid");
                require(spec.rows() != null && spec.rows() > 0, "layout.rows required for grid");
                yield ContainerLayout.grid(spec.cols(), spec.rows());
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

    public record Request(String id, String name, LayoutSpec layout, Float labelScale) {}

    public record LayoutSpec(String kind, Integer cols, Integer rows, Integer count, String prefix) {}
}
