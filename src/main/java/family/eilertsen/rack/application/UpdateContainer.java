package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Edits the parts of a container that carry no data: its display name and label scale.
 * The slot list is deliberately immutable here — reshaping a container would orphan slots that hold items.
 */
@Service
public class UpdateContainer {

    private final ContainerRegistry registry;

    public UpdateContainer(ContainerRegistry registry) {
        this.registry = registry;
    }

    public Container execute(ContainerId id, Fields fields) {
        Container existing = registry.get(id)
            .orElseThrow(() -> new NoSuchElementException("Unknown container: " + id.value()));

        String name = existing.name();
        if (fields.name() != null) {
            if (fields.name().isBlank()) throw new IllegalArgumentException("name must not be blank");
            name = fields.name().trim();
        }

        float scale = fields.labelScale() == null
            ? existing.labelScale()
            : Container.validLabelScale(fields.labelScale());

        Container updated = new Container(id, name, existing.slots(), scale);
        registry.update(updated);
        return updated;
    }

    /** Absent (null) fields are left as they are. */
    public record Fields(String name, Float labelScale) {}
}
