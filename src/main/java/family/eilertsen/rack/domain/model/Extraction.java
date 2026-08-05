package family.eilertsen.rack.domain.model;

/**
 * One item the vision model found, plus which of the supplied photos shows it.
 * A batch of photos is a set of views of one slot, so the index is what lets an
 * item keep pointing at the frame it was actually read from.
 */
public record Extraction(Item item, int imageIndex) {}
