package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.DrawerId;

import java.util.List;

public interface ImageStore {
    String store(DrawerId drawer, byte[] image, String contentType);

    byte[] read(DrawerId drawer, String filename);

    List<String> list(DrawerId drawer);
}
