package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SuggestSlot {

    private static final Logger log = LoggerFactory.getLogger(SuggestSlot.class);

    private static final int TOP_N = 5;

    /**
     * What a drawer scores for holding something the model called the same
     * kind of thing as the photographed one. Read off the whole listing with
     * every description in view, so it is worth about what a name match on
     * three words is — enough to put a drawer on the list by itself, which is
     * what "Samsung 16GB microSDHC" needed: the keyword pass could not get
     * past the brand and the capacity to the drawer of Transcend and SanDisk
     * cards, and the expander saw a word the rack already used and declined
     * to widen.
     */
    static final double LIKENESS = 9.0;

    private final PartExtractor extractor;
    private final FindItems find;
    private final FindCompanions companions;

    public SuggestSlot(PartExtractor extractor, FindItems find, FindCompanions companions) {
        this.extractor = extractor;
        this.find = find;
        this.companions = companions;
    }

    public Result execute(List<byte[]> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }
        List<Item> extracted = extractor.extract(photos).stream().map(Extraction::item).toList();
        Map<Key, Bucket> buckets = new LinkedHashMap<>();

        for (Item queried : extracted) {
            FindItems.Result found = find.forPhotographed(queried);
            // A suggestion that finds nothing is not stored anywhere, so this is
            // the only record of what the extractor called the thing and what
            // that name found. An SD card that got no drawer is otherwise
            // indistinguishable from a call that never happened.
            log.info("Suggest: \"{}\" (pn {}, tags {}) found {} item(s){}",
                queried.name(), queried.partNumber(), queried.tags(), found.hits().size(),
                found.expandedTerms().isEmpty() ? "" : " after widening to " + found.expandedTerms());
            for (SearchHit hit : found.hits()) {
                Key k = new Key(hit.container(), hit.slot());
                Bucket b = buckets.computeIfAbsent(k, key -> new Bucket(key, hit.lastVerified()));
                b.score += hit.score();
                if (!containsItem(b.matches, hit.item())) {
                    b.matches.add(hit.item());
                }
            }
        }

        // Filing is the moment a pair gets split: a charger filed by likeness
        // goes in with the other chargers, and the phone it belongs to stays
        // where it was. So the drawer holding the counterpart is offered here,
        // beside the drawers holding the same kind of thing. Not yet filed, so
        // there is no slot of its own to leave out. The same call also says
        // what the thing is the same kind of as, with every description in
        // view, which is a drawer suggestion in its own right.
        List<FindCompanions.Result> goesWith = new ArrayList<>();
        for (FindCompanions.Result found : companions.executeAll(extracted)) {
            if (!found.hits().isEmpty()) goesWith.add(found);
            for (FindCompanions.Found like : found.alike()) {
                Key k = new Key(like.container(), like.slot());
                Bucket b = buckets.computeIfAbsent(k, key -> new Bucket(key, like.lastVerified()));
                b.score += LIKENESS;
                if (!containsItem(b.matches, like.item())) b.matches.add(like.item());
            }
            if (!found.alike().isEmpty()) {
                log.info("Suggest: \"{}\" is the same kind as {}", found.query(),
                    found.alike().stream().map(f -> f.container().value() + "/" + f.slot().value() + " " + f.item().name()).toList());
            }
        }

        List<Suggestion> suggestions = buckets.values().stream()
            .sorted(Comparator.comparingDouble((Bucket b) -> -b.score))
            .limit(TOP_N)
            .map(b -> new Suggestion(b.key.container, b.key.slot, b.score, b.matches, b.lastVerified))
            .toList();

        log.info("Suggest: {} suggestion(s) {}", suggestions.size(),
            suggestions.stream().map(sg -> sg.container().value() + "/" + sg.slot().value()).toList());
        return new Result(extracted, suggestions, goesWith);
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

    /** {@code companions}: per extracted item that has one, where its counterpart is. */
    public record Result(List<Item> extracted, List<Suggestion> suggestions, List<FindCompanions.Result> companions) {}
}
