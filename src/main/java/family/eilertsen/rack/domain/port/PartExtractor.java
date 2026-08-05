package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Extraction;

import java.util.List;

public interface PartExtractor {
    /**
     * Extracts the distinct items visible across photos of a single slot. The
     * photos are different views of the same contents, so one part appearing in
     * several frames is one extraction, not several.
     */
    List<Extraction> extract(List<byte[]> images);
}
