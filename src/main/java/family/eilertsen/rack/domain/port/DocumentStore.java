package family.eilertsen.rack.domain.port;

import java.util.List;

/**
 * Documents in one flat folder for the whole rack, the way {@link ImageStore}
 * keeps photographs — and for the same reason: the thing that owns a file may be
 * renamed, moved or deleted, and the file should not have to move with it.
 */
public interface DocumentStore {

    /** Returns the stored filename, which may differ from the one offered. */
    String store(byte[] bytes, String originalFilename, String contentType);

    byte[] read(String filename);

    List<String> all();

    void delete(String filename);
}
