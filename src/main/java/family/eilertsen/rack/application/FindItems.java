package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
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

    /** Below this many literal hits the query is treated as one that didn't land. */
    private static final int ENOUGH_HITS = 5;

    /** Expanded terms rank below what the user literally typed. */
    private static final double EXPANSION_WEIGHT = 0.6;

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
        if (query == null || query.isBlank() || literal.size() >= ENOUGH_HITS) {
            return new Result(query, List.of(), literal);
        }

        List<String> terms = expandedTermsFor(query);
        if (terms.isEmpty()) return new Result(query, List.of(), literal);

        Map<Key, SearchHit> merged = new LinkedHashMap<>();
        for (SearchHit hit : literal) merged.put(Key.of(hit), hit);
        for (String term : terms) {
            for (SearchHit hit : index.searchByKeyword(term)) {
                SearchHit weighted = weigh(hit, EXPANSION_WEIGHT);
                merged.merge(Key.of(hit), weighted,
                    (existing, candidate) -> existing.score() >= candidate.score() ? existing : candidate);
            }
        }

        List<SearchHit> hits = new ArrayList<>(merged.values());
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new Result(query, terms, hits);
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
            hit.score() * weight, hit.lastVerified(), hit.photos());
    }

    /** One item is one hit however many terms found it. */
    private record Key(ContainerId container, SlotId slot, int index) {
        static Key of(SearchHit hit) {
            return new Key(hit.container(), hit.slot(), hit.index());
        }
    }

    public record Result(String query, List<String> expandedTerms, List<SearchHit> hits) {}
}
