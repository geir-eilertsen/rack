package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * A photo taken for a shopping line, read the way a drawer is: the same vision
 * call, so the line says what the camera can — name, part number, brand, the
 * details that tell it from its neighbour on the shelf — and the brand says
 * which shops, through the same rules an item's does.
 *
 * <p>Writes nothing. The page fills its empty boxes from the reading and the
 * person adds the line, or corrects it first; a reading is a suggestion, and
 * the photo is still the thing to hold up in the aisle.
 */
@Service
public class ReadForShopping {

    private final PartExtractor extractor;
    private final Shopping shopping;

    public ReadForShopping(PartExtractor extractor, Shopping shopping) {
        this.extractor = extractor;
        this.shopping = shopping;
    }

    /**
     * {@code others}: how many more things the photo showed, so the page can
     * say it read the clearest one rather than pretend there was one.
     */
    public record Reading(String what, String partNumber, String brand, String details,
                          List<String> stores, String because, int others) {}

    public Reading read(byte[] photo) {
        List<Extraction> found = extractor.extract(List.of(photo), null);
        if (found == null || found.isEmpty()) {
            throw new IllegalArgumentException("Could not tell what that is — try closer, with the label in view");
        }
        // The thing the photo is of is the one it was surest about.
        Item best = found.stream().map(Extraction::item)
            .max(Comparator.comparingDouble(Item::confidence)).orElseThrow();

        boolean named = best.name() != null && !best.name().isBlank();
        String what = named ? best.name() : clip(best.description());
        Shopping.StoreHint hint = shopping.storeFor(best).orElse(null);
        return new Reading(what, best.partNumber(), best.brand(), named ? best.description() : null,
            hint == null ? List.of() : hint.stores(), hint == null ? null : hint.because(), found.size() - 1);
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
