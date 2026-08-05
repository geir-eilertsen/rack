package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Search by holding the part up to the camera, for when you have the thing in
 * your hand and no idea what it is called — which is the case the typed search
 * serves worst, because you cannot type a name you do not know.
 *
 * <p>Same pipeline as filing a slot, read the other way round: the vision model
 * says what it sees and each item is looked up exactly as {@link SuggestSlot}
 * looks it up, so a photo and a good guess at the name find the same drawer.
 */
@Service
public class FindByPhoto {

    private final PartExtractor extractor;
    private final FindItems find;

    public FindByPhoto(PartExtractor extractor, FindItems find) {
        this.extractor = extractor;
        this.find = find;
    }

    public FindItems.Result execute(List<byte[]> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }
        List<Item> extracted = extractor.extract(photos).stream().map(Extraction::item).toList();

        Map<Key, SearchHit> merged = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        Set<String> terms = new LinkedHashSet<>();

        for (Item photographed : extracted) {
            if (photographed.name() != null && !photographed.name().isBlank()) {
                names.add(photographed.name().strip());
            }
            FindItems.Result result = find.forPhotographed(photographed);
            terms.addAll(result.expandedTerms());
            for (SearchHit hit : result.hits()) {
                // A frame usually holds several things; the same drawer answering
                // for two of them is one row, at its best score.
                merged.merge(new Key(hit.container(), hit.slot(), hit.index()), hit,
                    (existing, candidate) -> existing.score() >= candidate.score() ? existing : candidate);
            }
        }

        List<SearchHit> hits = new ArrayList<>(merged.values());
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new FindItems.Result(String.join(", ", names), List.copyOf(terms), hits);
    }

    private record Key(ContainerId container, SlotId slot, int index) {}
}
