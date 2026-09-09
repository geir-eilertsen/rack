package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.QueryExpander;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keyword search first, and only when that comes up short does the query get
 * expanded into the words this rack actually uses.
 *
 * <p>Gating on the literal pass matters: a query that already works ("BC547",
 * "transistor") answers instantly and costs nothing, and only the queries that
 * failed — "isolating tape" — pay for a model call.
 */
@Service
public class FindItems {

    /**
     * The weight of a name or part-number match — the score of a hit that
     * actually answers the query.
     *
     * <p>The gate is the <em>best</em> score and not the number of rows, because
     * a row count lies. Searching "sugekopp for lodding" matched six items on the
     * word "for" alone, all scoring 1.2, and a count-based gate read that as a
     * search that worked and skipped the expansion the query most needed.
     */
    private static final double CONVINCING = 3.0;

    /** Expanded terms rank below what the user literally typed. */
    private static final double EXPANSION_WEIGHT = 0.6;

    /**
     * The query with a word left out ranks between the two: it is still the
     * user's own words, but not all of them.
     */
    private static final double IGNORING_WEIGHT = 0.8;

    private static final int CACHE_SIZE = 200;

    private final PartIndex index;
    private final QueryExpander expander;

    /** Keystrokes converge on the same few queries; an expansion is worth keeping. */
    private final Map<String, List<String>> expansions = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    public FindItems(PartIndex index, QueryExpander expander) {
        this.index = index;
        this.expander = expander;
    }

    /** Literal keyword search only — fast enough to run on every keystroke. */
    public Result literal(String query) {
        return new Result(query, List.of(), index.searchByKeyword(query));
    }

    /**
     * Literal search, widened with related terms when the literal pass came up
     * short. Returns the same shape as {@link #literal}, with the terms that were
     * added so the UI can say what it broadened to.
     */
    public Result smart(String query) {
        List<SearchHit> literal = index.searchByKeyword(query);
        if (query == null || query.isBlank() || landed(literal)) {
            return new Result(query, List.of(), literal);
        }

        List<String> terms = expandedTermsFor(query);

        Map<Key, SearchHit> merged = new LinkedHashMap<>();
        for (SearchHit hit : literal) merged.put(Key.of(hit), hit);
        for (String term : terms) {
            for (SearchHit hit : index.searchByKeyword(term)) keepBest(merged, weigh(hit, EXPANSION_WEIGHT));
        }

        // Neither pass can rescue a query with a word in it the rack never
        // uses: every word must match, and the expander adds words and never
        // takes one away. "wireless computer mouse" found nothing for a
        // Logitech wireless mouse over "computer". So, last: leave out the
        // words no item contains at all and search the rest — and only keep
        // that when it lands, or when there was nothing else, so "isolating
        // tape" widened to the electrical tape does not also drag in the
        // resistors on tape reels.
        List<String> ignored = List.of();
        if (!landed(sorted(merged))) {
            Ignoring without = withoutAbsentWords(query);
            if (!without.ignored().isEmpty() && (landed(without.hits()) || merged.isEmpty())) {
                ignored = without.ignored();
                for (SearchHit hit : without.hits()) keepBest(merged, hit);
            }
        }

        return new Result(query, terms, sorted(merged), ignored);
    }

    /**
     * The query minus the words no item contains at all. A word absent from the
     * whole index cannot be satisfied by any item, so requiring it is a
     * guarantee of nothing; the rest of the query is still the user's own words
     * and is searched under the same all-words rule.
     */
    private Ignoring withoutAbsentWords(String query) {
        List<String> kept = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String word : query.strip().split("\\s+")) {
            if (word.length() < 2) continue;
            if (index.searchByKeyword(word).isEmpty()) ignored.add(word);
            else kept.add(word);
        }
        if (ignored.isEmpty() || kept.isEmpty()) return new Ignoring(List.of(), List.of());
        List<SearchHit> hits = new ArrayList<>();
        for (SearchHit hit : index.searchByKeyword(String.join(" ", kept))) hits.add(weigh(hit, IGNORING_WEIGHT));
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new Ignoring(List.copyOf(ignored), List.copyOf(hits));
    }

    private record Ignoring(List<String> ignored, List<SearchHit> hits) {}

    private static void keepBest(Map<Key, SearchHit> merged, SearchHit candidate) {
        merged.merge(Key.of(candidate), candidate,
            (existing, incoming) -> existing.score() >= incoming.score() ? existing : incoming);
    }

    private static List<SearchHit> sorted(Map<Key, SearchHit> merged) {
        List<SearchHit> hits = new ArrayList<>(merged.values());
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    /**
     * Hits for an item a vision model just described, whether that photo is
     * being filed or searched for.
     *
     * <p>The name is the short label the expander is built for, so it is the one
     * query allowed to widen. A part number is already precise. Tags only
     * corroborate: a tag is a single generic word, so the rule that every word
     * must match has nothing to bite on — the tag "tape" alone matched
     * twenty-two resistors that come on tape reels. A tag raises an item the
     * name or part number already found and can never add one on its own, so an
     * item with neither — barely an identification — finds nothing rather than
     * guessing from its tags.
     */
    public Result forPhotographed(Item item) {
        Map<Key, SearchHit> found = new LinkedHashMap<>();
        List<String> terms = new ArrayList<>();
        List<String> ignored = List.of();

        if (notBlank(item.partNumber())) addAll(found, literal(item.partNumber()).hits());
        if (notBlank(item.name())) {
            Result byName = smart(item.name());
            terms = byName.expandedTerms();
            ignored = byName.ignoredWords();
            addAll(found, byName.hits());
        }
        if (item.tags() != null) {
            for (String tag : item.tags()) {
                if (!notBlank(tag)) continue;
                for (SearchHit hit : literal(tag).hits()) {
                    found.computeIfPresent(Key.of(hit), (k, anchored) -> plus(anchored, hit.score()));
                }
            }
        }

        List<SearchHit> hits = new ArrayList<>(found.values());
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new Result(item.name(), terms, hits, ignored);
    }

    /** Two terms agreeing on an item is a stronger signal than either alone. */
    private static void addAll(Map<Key, SearchHit> found, List<SearchHit> hits) {
        for (SearchHit hit : hits) {
            found.merge(Key.of(hit), hit, (existing, extra) -> plus(existing, extra.score()));
        }
    }

    private static SearchHit plus(SearchHit hit, double score) {
        return new SearchHit(hit.container(), hit.slot(), hit.index(), hit.item(),
            hit.score() + score, hit.lastVerified());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Hits are sorted best first, so one convincing hit is the whole question. */
    private static boolean landed(List<SearchHit> hits) {
        return !hits.isEmpty() && hits.get(0).score() >= CONVINCING;
    }

    private List<String> expandedTermsFor(String query) {
        String key = query.strip().toLowerCase(Locale.ROOT);
        List<String> cached = expansions.get(key);
        if (cached != null) return cached;
        List<String> terms = expander.expand(query, index.vocabulary());
        expansions.put(key, terms);
        return terms;
    }

    private static SearchHit weigh(SearchHit hit, double weight) {
        return new SearchHit(hit.container(), hit.slot(), hit.index(), hit.item(),
            hit.score() * weight, hit.lastVerified());
    }

    /** One item is one hit however many terms found it. */
    private record Key(ContainerId container, SlotId slot, int index) {
        static Key of(SearchHit hit) {
            return new Key(hit.container(), hit.slot(), hit.index());
        }
    }

    /**
     * {@code ignoredWords} are the words of the query that no item contains and
     * that were left out to find anything at all — named so the page can say
     * so, because a hit that does not match every word typed needs explaining.
     */
    public record Result(String query, List<String> expandedTerms, List<SearchHit> hits, List<String> ignoredWords) {
        public Result {
            ignoredWords = ignoredWords == null ? List.of() : List.copyOf(ignoredWords);
        }

        public Result(String query, List<String> expandedTerms, List<SearchHit> hits) {
            this(query, expandedTerms, hits, List.of());
        }
    }
}
