package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;

import java.util.List;

public interface ImageStore {
    String store(ContainerId container, SlotId slot, byte[] image, String contentType);

    byte[] read(ContainerId container, SlotId slot, String filename);

    List<String> list(ContainerId container, SlotId slot);

    /**
     * Drops a frame the slot no longer records. A photo that has already gone —
     * hand-deleted, or a resync run twice — is the state this asks for, so it is
     * not an error: refiling a drawer must never fail over a file that was
     * already absent.
     */
    void delete(ContainerId container, SlotId slot, String filename);
}
