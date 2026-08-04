package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;

import java.util.List;

public interface ImageStore {
    String store(ContainerId container, SlotId slot, byte[] image, String contentType);

    byte[] read(ContainerId container, SlotId slot, String filename);

    List<String> list(ContainerId container, SlotId slot);
}
