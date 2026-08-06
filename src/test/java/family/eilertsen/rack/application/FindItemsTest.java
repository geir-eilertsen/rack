package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.QueryExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FindItemsTest {

    private static final ContainerId RACK = new ContainerId("rack");

    private FakeIndex index;
    private FakeExpander expander;
    private FindItems find;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        expander = new FakeExpander();
        find = new FindItems(index, expander);
    }

    @Test
    void widensAQueryThatFoundNothingIntoTheWordsTheRackUses() {
        // The whole point: nothing is described as "isolating tape".
        index.hits("electrical tape", hit("A1", 0, 5));
        expander.returns("electrical tape", "insulation tape");

        FindItems.Result result = find.smart("isolating tape");

        assertThat(result.expandedTerms()).containsExactly("electrical tape", "insulation tape");
        assertThat(result.hits()).extracting(SearchHit::slot).containsExactly(new SlotId("A1"));
    }

    @Test
    void leavesAQueryThatAlreadyWorksAlone() {
        index.hits("transistor", hit("A1", 0, 4), hit("A2", 0, 3), hit("A3", 0, 3));

        FindItems.Result result = find.smart("transistor");

        assertThat(result.hits()).hasSize(3);
        assertThat(result.expandedTerms()).isEmpty();
        assertThat(expander.calls).isEmpty();
    }

    @Test
    void aPileOfWeakHitsIsNotASearchThatWorked() {
        // "sugekopp for lodding" against a rack with no desoldering pump: both
        // words that carry the meaning miss, "for" matches six descriptions, and
        // counting rows would call that a hit and skip the expansion.
        index.hits("sugekopp for lodding", hit("D1", 0, 1.2), hit("C1", 0, 1.2),
            hit("C2", 0, 1.2), hit("C3", 0, 1.2), hit("C4", 0, 1.2), hit("C5", 0, 1.2));
        index.hits("desoldering pump", hit("A1", 0, 6));
        expander.returns("desoldering pump");

        FindItems.Result result = find.smart("sugekopp for lodding");

        assertThat(expander.calls).containsExactly("sugekopp for lodding");
        assertThat(result.hits().get(0).slot()).isEqualTo(new SlotId("A1"));
    }

    @Test
    void literalMatchesOutrankTheOnesFoundByAWidenedTerm() {
        index.hits("tape", hit("B2", 0, 2.5));
        index.hits("electrical tape", hit("A1", 0, 4));
        expander.returns("electrical tape");

        FindItems.Result result = find.smart("tape");

        // The expanded hit scored higher literally (4 > 2.5) but is discounted to
        // 2.4, so what the user actually typed still comes first.
        assertThat(result.hits()).extracting(SearchHit::slot)
            .containsExactly(new SlotId("B2"), new SlotId("A1"));
    }

    @Test
    void oneItemFoundByBothPassesIsOneHit() {
        index.hits("tape", hit("A1", 0, 2.5));
        index.hits("electrical tape", hit("A1", 0, 5));
        expander.returns("electrical tape");

        FindItems.Result result = find.smart("tape");

        assertThat(result.hits()).hasSize(1);
        // Kept at the better of the two scores: 5 × 0.6 beats the literal 2.5.
        assertThat(result.hits().get(0).score()).isEqualTo(3.0);
    }

    @Test
    void theLiteralPassNeverCallsTheModel() {
        index.hits("isolating tape");

        FindItems.Result result = find.literal("isolating tape");

        assertThat(result.hits()).isEmpty();
        assertThat(result.expandedTerms()).isEmpty();
        assertThat(expander.calls).isEmpty();
    }

    @Test
    void anExpansionIsAskedForOnceAndThenRemembered() {
        index.hits("electrical tape", hit("A1", 0, 5));
        expander.returns("electrical tape");

        find.smart("isolating tape");
        find.smart("Isolating Tape");

        assertThat(expander.calls).hasSize(1);
    }

    @Test
    void survivesAnExpanderThatCannotAnswer() {
        index.hits("isolating tape");
        expander.returns();

        FindItems.Result result = find.smart("isolating tape");

        assertThat(result.hits()).isEmpty();
        assertThat(result.expandedTerms()).isEmpty();
    }

    private static SearchHit hit(String slot, int itemIndex, double score) {
        Item item = new Item("tape", "a roll of tape", null, "other", 1, 0.9, List.of(), List.of(), null, null);
        return new SearchHit(RACK, new SlotId(slot), itemIndex, item, score, null);
    }

    private static final class FakeExpander implements QueryExpander {
        private final List<String> calls = new ArrayList<>();
        private List<String> terms = List.of();

        void returns(String... expanded) {
            this.terms = List.of(expanded);
        }

        @Override
        public List<String> expand(String query, Collection<String> vocabulary) {
            calls.add(query);
            return terms;
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<String, List<SearchHit>> byQuery = new LinkedHashMap<>();

        void hits(String query, SearchHit... hits) {
            byQuery.put(query.toLowerCase(Locale.ROOT), List.of(hits));
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return byQuery.getOrDefault(query.toLowerCase(Locale.ROOT), List.of());
        }

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.empty();
        }

        @Override
        public void save(ContainerId container, Slot slot) {
        }

        @Override
        public Collection<Slot> all(ContainerId container) {
            return List.of();
        }


        @Override
        public void forget(ContainerId container) {
            byQuery.clear();
        }

        @Override
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of("electrical tape", "heat shrink");
        }
    }
}
