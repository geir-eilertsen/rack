package family.eilertsen.rack.domain.port;

/**
 * Photographs, kept in one place rather than under the slot they were taken of.
 *
 * <p>A frame is a picture of some things that happened to be in a drawer at a
 * moment, and filing it under that drawer encodes an ownership that stops being
 * true twice over: eighteen frames in this rack are referenced by more than one
 * item, one of them by twenty-two, and an item that moves takes its references
 * with it. When the file lived under the slot, those references resolved against
 * the new drawer's directory and answered 404 — so a moved item had to be
 * stripped of its photographs to avoid showing a broken one.
 */
public interface ImageStore {

    /** Returns the filename it was stored under, which is unique across the rack. */
    String store(byte[] image, String contentType);

    byte[] read(String filename);

    /** Deleting a file that is already gone is not an error. */
    void delete(String filename);
}
