package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Item;

import java.util.List;

public interface PartExtractor {
    List<Item> extract(byte[] image);
}
