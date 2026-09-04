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
import java.util.regex.Pattern;

/**
 * What an item goes with, and where that is.
 *
 * <p>A phone in one box and its charger in another are both filed correctly
 * and both findable, and neither entry says anything about the other — so the
 * rack is right about both and still sends you to two rooms for one thing.
 * This is the counterpart lookup: the expander is asked what this item is used
 * <em>with</em>, in the words this rack uses, and each answer is searched the
 * way an expanded term is. Hits already in the item's own slot are reported
 * apart from the rest, because the question is what to bring together, and
 * those already are — the Lumix camera sits beside its charger, and a page
 * saying it found nothing anywhere else would be hiding the pair it found.
 *
 * <p>It is a suggestion and never a move. The offer is the counterpart's
 * drawer with a move action beside it; which half moves is the user's call,
 * because only they know which box is the phone's home.
 */
@Service
public class FindCompanions {

    private static final int MAX_HITS = 5;

    /**
     * The weight of a name or part-number match, as in FindItems. A counterpart
     * has to be found by what it is called: "automotive" is a tag on the paper
     * towels, and a tag-only hit is a category in common, not a pair.
     */
    private static final double CONVINCING = 3.0;

    /** Three letters in a row somewhere: "5V" and "230V" name a property, not a thing. */
    private static final Pattern NAMES_A_THING = Pattern.compile("\\p{L}{3}");
    private static final int CACHE_SIZE = 200;

    private final PartIndex index;
    private final QueryExpander expander;

    /** The same items get looked at over and over; a counterpart list is worth keeping. */
    private final Map<String, List<String>> counterparts = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    public FindCompanions(PartIndex index, QueryExpander expander) {
        this.index = index;
        this.expander = expander;
    }

    /**
     * Counterparts of a stored item, from anywhere but its own slot.
     *
     * @param container where the item is; null for an item not yet filed, which
     *                  has no slot to leave out
     */
    public Result execute(Item item, ContainerId container, SlotId slot) {
        String name = item == null ? null : item.name();
        if (name == null || name.isBlank()) return new Result(name, List.of(), List.of(), List.of());

        List<String> terms = termsFor(name).stream().filter(FindCompanions::namesAThing).toList();
        if (terms.isEmpty()) return new Result(name, terms, List.of(), List.of());

        Map<Key, SearchHit> elsewhere = new LinkedHashMap<>();
        Map<Key, SearchHit> here = new LinkedHashMap<>();
        for (String term : terms) {
            for (SearchHit hit : index.searchByKeyword(term)) {
                if (hit.score() < CONVINCING || sameKind(hit.item(), name)) continue;
                Map<Key, SearchHit> into = sameSlot(hit, container, slot) ? here : elsewhere;
                into.merge(Key.of(hit), hit,
                    (existing, candidate) -> existing.score() >= candidate.score() ? existing : candidate);
            }
        }

        return new Result(name, terms, best(elsewhere), best(here));
    }

    private static List<SearchHit> best(Map<Key, SearchHit> found) {
        List<SearchHit> hits = new ArrayList<>(found.values());
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (hits.size() > MAX_HITS) hits = hits.subList(0, MAX_HITS);
        return List.copyOf(hits);
    }

    private List<String> termsFor(String name) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        List<String> cached = counterparts.get(key);
        if (cached != null) return cached;
        List<String> terms = expander.goesWith(name, index.vocabulary());
        counterparts.put(key, terms);
        return terms;
    }

    private static boolean sameSlot(SearchHit hit, ContainerId container, SlotId slot) {
        return container != null && slot != null
            && hit.container().equals(container) && hit.slot().equals(slot);
    }

    /**
     * A second charger is not a counterpart of a charger. The prompt says so,
     * but a term like "phone" searched literally still matches "Phone charger",
     * so the hit's name is read for what kind of thing it names: a compound
     * is named by its last word — a phone charger is a charger, an old phone
     * is a phone — and a hit naming the same kind as the item is another of
     * the same thing rather than its other half. Word overlap will not do:
     * "Phone charger" shares a word with "Phone" and is exactly the answer.
     */
    static boolean sameKind(Item other, String name) {
        String a = head(name);
        String b = other == null ? null : head(other.name());
        if (a == null || b == null) return false;
        return a.equals(b) || (a + "s").equals(b) || (b + "s").equals(a);
    }

    /**
     * Asked what a USB wall outlet goes with, the model answered "5V" and
     * "mains powered" alongside the AC/DC adapter — properties of the thing,
     * not things — and "5V" is a substring of every 35V capacitor's name. A
     * counterpart is something on a shelf, so a term with no word in it is
     * not one.
     */
    static boolean namesAThing(String term) {
        return term != null && NAMES_A_THING.matcher(term).find();
    }

    private static String head(String name) {
        if (name == null || name.isBlank()) return null;
        String[] words = name.strip().toLowerCase(Locale.ROOT).split("\\s+");
        return words[words.length - 1];
    }

    private record Key(ContainerId container, SlotId slot, int index) {
        static Key of(SearchHit hit) {
            return new Key(hit.container(), hit.slot(), hit.index());
        }
    }

    /**
     * The name asked about, the words it was bridged to, where they are
     * ({@code hits}, anywhere but the item's own slot) and which of them are
     * already beside it ({@code together}).
     */
    public record Result(String query, List<String> terms, List<SearchHit> hits, List<SearchHit> together) {}
}
