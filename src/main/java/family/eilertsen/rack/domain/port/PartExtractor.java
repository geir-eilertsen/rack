package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Extraction;

import java.util.List;

public interface PartExtractor {
    /**
     * Extracts the distinct items visible across photos of a single slot. The
     * photos are different views of the same contents, so one part appearing in
     * several frames is one extraction, not several.
     *
     * <p>{@code note} is what the person holding the parts says about them that
     * no photograph can show — a thread and a length measured with calipers, a
     * wire gauge, a value read off a meter. Null or blank when they said
     * nothing. Where it speaks it outranks what the model can read, because the
     * model cannot tell an M4×16 from an M4×20 and the person just measured it.
     */
    List<Extraction> extract(List<byte[]> images, String note);

    /** A batch with nothing said about it. */
    default List<Extraction> extract(List<byte[]> images) {
        return extract(images, null);
    }
}
