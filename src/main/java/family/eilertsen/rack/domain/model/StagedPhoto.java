package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * A photograph shot and not yet filed. {@code container} and {@code slot} are
 * where the page was pointed when it was shot, so a relaunch can go back
 * there; either may be null when no drawer had been chosen yet.
 */
public record StagedPhoto(String id, Instant at, ContainerId container, SlotId slot) {}
