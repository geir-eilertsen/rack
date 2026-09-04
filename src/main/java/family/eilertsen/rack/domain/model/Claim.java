package family.eilertsen.rack.domain.model;

/**
 * A pairing the model proposed, put to it again on its own: the two entries
 * as it saw them and the reason it gave. Picking candidates out of a listing
 * of a hundred and fifty lines and judging two entries side by side are
 * different questions, and the second is the one it answers well.
 */
public record Claim(String subject, String candidate, String why) {}
