package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartExtractor;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SuggestSlot {

    private static final int TOP_N = 5;

    private final PartExtractor extractor;
    private final PartIndex index;

    public SuggestSlot(PartExtractor extractor, PartIndex index) {
        this.extractor = extractor;
        this.index = index;
    }

    public Result execute(List<byte[]> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }
        List<Item> extracted = extractor.extract(photos).stream().map(Extraction::item).toList();
        Map<Key, Bucket> buckets = new LinkedHashMap<>();

        for (Item queried : extracted) {
            for (String term : searchTermsFor(queried)) {
                for (SearchHit hit : index.searchByKeyword(term)) {
                    Key k = new Key(hit.container(), hit.slot());
                    Bucket b = buckets.computeIfAbsent(k, key -> new Bucket(key, hit.lastVerified()));
                    b.score += hit.score();
                    if (!containsItem(b.matches, hit.item())) {
                        b.matches.add(hit.item());
                    }
                }
            }
        }

        List<Suggestion> suggestions = buckets.values().stream()
            .sorted(Comparator.comparingDouble((Bucket b) -> -b.score))
            .limit(TOP_N)
            .map(b -> new Suggestion(b.key.container, b.key.slot, b.score, b.matches, b.lastVerified))
            .toList();

        return new Result(extracted, suggestions);
    }

    private static List<String> searchTermsFor(Item item) {
        List<String> terms = new ArrayList<>();
        if (item.partNumber() != null && !item.partNumber().isBlank()) terms.add(item.partNumber());
        if (item.tags() != null) {
            for (String tag : item.tags()) if (tag != null && !tag.isBlank()) terms.add(tag);
        }
        return terms;
    }

    private static boolean containsItem(List<Item> list, Item item) {
        for (Item i : list) if (i == item) return true;
        return false;
    }

    private record Key(ContainerId container, SlotId slot) {}

    private static final class Bucket {
        final Key key;
        final Instant lastVerified;
        final List<Item> matches = new ArrayList<>();
        double score;
        Bucket(Key key, Instant lastVerified) {
            this.key = key;
            this.lastVerified = lastVerified;
        }
    }

    public record Suggestion(
        ContainerId container,
        SlotId slot,
        double score,
        List<Item> matches,
        Instant lastVerified
    ) {}

    public record Result(List<Item> extracted, List<Suggestion> suggestions) {}
}
