package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * One print run: where it started on its sheet and how many stickers it took.
 *
 * <p>Recorded rather than re-derived. Sheet position used to be worked out by
 * re-packing whatever is currently marked printed, which makes it a function of
 * present state — edit a slot and the sheet moves under you. A sticker that has
 * been peeled off is a fact.
 */
public record LabelRun(Instant at, String container, int startedAt, int stickers) {}
