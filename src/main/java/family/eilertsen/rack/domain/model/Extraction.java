package family.eilertsen.rack.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One item the vision model found, plus which of the supplied photos show it.
 *
 * <p>A batch is a set of views of one slot, and the model merges them into a
 * single item — a part from the front, the same part from the side, its label on
 * a third frame. It knows which frames it merged, so it is asked for all of
 * them. The first is the one it considers clearest and becomes the item's
 * thumbnail; the rest are what makes "photos of this item" answerable at all.
 */
public record Extraction(Item item, List<Integer> imageIndexes) {

    /**
     * The same frame named twice says nothing the first mention didn't, and the
     * model does repeat itself — so normalise here, where every producer passes,
     * rather than in whichever adapter happens to be calling.
     */
    public Extraction {
        List<Integer> distinct = new ArrayList<>();
        if (imageIndexes != null) {
            for (Integer index : imageIndexes) {
                if (index != null && !distinct.contains(index)) distinct.add(index);
            }
        }
        imageIndexes = distinct.isEmpty() ? List.of(0) : List.copyOf(distinct);
    }

    /** A single frame is just a batch of one. */
    public Extraction(Item item, int imageIndex) {
        this(item, List.of(imageIndex));
    }

    /** The frame the model put first — the item's thumbnail. */
    public int imageIndex() {
        return imageIndexes.get(0);
    }
}
