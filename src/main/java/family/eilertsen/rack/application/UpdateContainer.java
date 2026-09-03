package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Edits the parts of a container that carry no data: what it is called, where it is,
 * whatever else is worth writing down about it, and how big its labels print.
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

        String slotLabel = fields.slotLabel() == null
            ? existing.slotLabel()
            : Container.validSlotLabel(fields.slotLabel());

        // Absent means unchanged; blank means cleared. A name has no third state —
        // every container has one — but a location it turns out you were wrong about
        // is better empty than wrong, so emptying the box is how you say so.
        String location = fields.location() == null
            ? existing.location()
            : Container.validLocation(fields.location());

        String notes = fields.notes() == null
            ? existing.notes()
            : Container.validNotes(fields.notes());

        Container updated = new Container(id, name, existing.slots(), scale, slotLabel, location, notes);
        registry.update(updated);
        return updated;
    }

    /** Absent (null) fields are left as they are; a blank location or note clears it. */
    public record Fields(String name, Float labelScale, String slotLabel, String location, String notes) {}
}
