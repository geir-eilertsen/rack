package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.FittedPhoto;
import family.eilertsen.rack.domain.model.StagedPhoto;
import family.eilertsen.rack.domain.model.SlotId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Where a camera frame becomes a photograph, and waits to be filed.
 *
 * <p>A phone's camera hands over a frame of tens of megapixels, and the page
 * used to decode it to make the photograph the rack keeps — a 48MB bitmap, on
 * a device that wants that memory back for the very next shot. Phones reported
 * low memory while photographing, froze, and killed the app while the camera
 * was open, which relaunched it on the front page with the batch gone. So the
 * frame is uploaded as it comes, the moment it is taken, and this is where it
 * is fitted to the size the vision model reads and turned the way the camera
 * saw it. The phone never decodes it at all.
 *
 * <p>A staged photograph is one that has been shot and not yet filed. It lives
 * here until the batch it is part of is filed, resynced against a drawer or
 * dropped from the strip; a relaunched page finds it again by asking. Nothing
 * in the index points at it, so it is not a photograph in {@link ImageStore}'s
 * sense — that is where it goes once an item names it.
 */
public interface PhotoStaging {

    /** Keeps a frame as a photograph, fitted and oriented. */
    StagedPhoto stage(byte[] frame, String contentType, ContainerId container, SlotId slot);

    /** The same fitting, for a frame that is used once and not kept — a photo search. */
    FittedPhoto fit(byte[] frame, String contentType);

    /** Every photograph waiting, oldest first. */
    List<StagedPhoto> all();

    /** The photograph, ready to file. */
    byte[] read(String id);

    /** A small copy for showing on the strip. */
    byte[] preview(String id);

    /** Removing one that is already gone is not an error. */
    void remove(String id);

    /** Drops photographs staged longer ago than {@code olderThan} and never filed; returns how many. */
    int sweep(Duration olderThan);
}
