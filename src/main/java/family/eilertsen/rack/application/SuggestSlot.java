package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartExtractor;
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
    private final FindItems find;

    public SuggestSlot(PartExtractor extractor, FindItems find) {
        this.extractor = extractor;
        this.find = find;
    }

    public Result execute(List<byte[]> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }
        List<Item> extracted = extractor.extract(photos).stream().map(Extraction::item).toList();
        Map<Key, Bucket> buckets = new LinkedHashMap<>();

        for (Item queried : extracted) {
            for (SearchHit hit : hitsFor(queried)) {
                Key k = new Key(hit.container(), hit.slot());
                Bucket b = buckets.computeIfAbsent(k, key -> new Bucket(key, hit.lastVerified()));
                b.score += hit.score();
                if (!containsItem(b.matches, hit.item())) {
                    b.matches.add(hit.item());
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

    /**
     * Filing has the same problem finding has: photograph a roll of tape, have
     * the extractor call it "Insulating tape", and the drawer already holding
     * "Electrical tape" never comes up — so the same roll gets filed twice.
     *
     * <p>The name is the short label the expander is built for, so it is the one
     * query allowed to widen. The part number and the tags are already precise —
     * and the tags are the extractor's own synonyms — so they stay literal, which
     * caps a batch at one model call per extracted item, and only for the items
     * whose name found nothing.
     */
    private List<SearchHit> hitsFor(Item item) {
        List<SearchHit> hits = new ArrayList<>();
        if (notBlank(item.partNumber())) hits.addAll(find.literal(item.partNumber()).hits());
        if (item.tags() != null) {
            for (String tag : item.tags()) if (notBlank(tag)) hits.addAll(find.literal(tag).hits());
        }
        if (notBlank(item.name())) hits.addAll(find.smart(item.name()).hits());
        return hits;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
