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
            for (Match match : matchesFor(queried)) {
                SearchHit hit = match.hit();
                Key k = new Key(hit.container(), hit.slot());
                Bucket b = buckets.computeIfAbsent(k, key -> new Bucket(key, hit.lastVerified()));
                b.score += hit.score();
                b.anchored |= match.anchoring();
                if (!containsItem(b.matches, hit.item())) {
                    b.matches.add(hit.item());
                }
            }
        }

        List<Suggestion> suggestions = buckets.values().stream()
            .filter(b -> b.anchored)
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
     *
     * <p>A name or part number <em>anchors</em> a slot; a tag only corroborates
     * one. A tag is a single generic word, so the rule that every word must match
     * can't discipline it: photograph a roll of tape and the tag "tape" alone
     * scored the resistor drawer 66, because twenty-two of them come on tape
     * reels. A tag can raise a slot the name already found and can't put one in
     * the list on its own.
     */
    private List<Match> matchesFor(Item item) {
        List<Match> matches = new ArrayList<>();
        if (notBlank(item.partNumber())) add(matches, find.literal(item.partNumber()).hits(), true);
        if (notBlank(item.name())) add(matches, find.smart(item.name()).hits(), true);
        if (item.tags() != null) {
            for (String tag : item.tags()) if (notBlank(tag)) add(matches, find.literal(tag).hits(), false);
        }
        return matches;
    }

    private static void add(List<Match> matches, List<SearchHit> hits, boolean anchoring) {
        for (SearchHit hit : hits) matches.add(new Match(hit, anchoring));
    }

    /** Whether the term that found this hit can put a slot in the list, or only raise it. */
    private record Match(SearchHit hit, boolean anchoring) {}

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
        boolean anchored;
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
