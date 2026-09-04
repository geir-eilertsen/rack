package family.eilertsen.rack.domain.model;

/**
 * One entry a thing belongs with, as the model cited it: which subject it is
 * about (zero-based, in the order the subjects were given), the reference of
 * a line in the listing it was shown, and what the one does for the other.
 * Nothing here is believed until the reference resolves to an item the index
 * actually holds.
 */
public record Companion(int subject, String ref, String why) {}
