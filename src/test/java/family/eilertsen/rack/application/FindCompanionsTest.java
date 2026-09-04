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

/**
 * A phone in one box and its charger in another: both filed correctly, both
 * findable, and the rack sends you to two rooms for one thing.
 */
class FindCompanionsTest {

    private static final ContainerId LAB = new ContainerId("lab");
    private static final ContainerId CELLAR = new ContainerId("cellar");

    private FakeIndex index;
    private FakeExpander expander;
    private FindCompanions companions;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        expander = new FakeExpander();
        companions = new FindCompanions(index, expander);
    }

    @Test
    void findsTheOtherHalfOfAPairInAnotherPlace() {
        expander.pairsWith("charger");
        index.hits("charger", hit(CELLAR, "3", "Phone charger", 3));

        FindCompanions.Result result = companions.execute(named("Phone"), LAB, new SlotId("A1"));

        assertThat(result.terms()).containsExactly("charger");
        assertThat(result.hits()).extracting(SearchHit::container).containsExactly(CELLAR);
    }

    @Test
    void leavesOutWhatIsAlreadyInTheSameSlot() {
        // The question is what to bring together, and this pair already is.
        expander.pairsWith("charger");
        index.hits("charger", hit(LAB, "A1", "Phone charger", 3), hit(CELLAR, "3", "Spare charger", 3));

        FindCompanions.Result result = companions.execute(named("Phone"), LAB, new SlotId("A1"));

        assertThat(result.hits()).extracting(SearchHit::container).containsExactly(CELLAR);
    }

    @Test
    void anItemNotYetFiledHasNoSlotToLeaveOut() {
        expander.pairsWith("charger");
        index.hits("charger", hit(LAB, "A1", "Phone charger", 3));

        FindCompanions.Result result = companions.execute(named("Phone"), null, null);

        assertThat(result.hits()).hasSize(1);
    }

    @Test
    void anotherOfTheSameThingIsNotACounterpart() {
        // "phone" searched literally matches the phone charger in the next
        // drawer as well as the phones. A phone charger is a charger — a
        // compound is named by its last word — so it is another of the same
        // thing as this charger, not what it goes with.
        expander.pairsWith("phone");
        index.hits("phone", hit(CELLAR, "3", "Phone", 3), hit(CELLAR, "4", "Old phone", 2), hit(LAB, "B2", "Phone charger", 1));

        FindCompanions.Result result = companions.execute(named("USB charger"), LAB, new SlotId("A1"));

        assertThat(result.hits()).extracting(h -> h.item().name()).containsExactly("Phone", "Old phone");
    }

    @Test
    void sharingAWordDoesNotMakeItTheSameThing() {
        // The headline case: "Phone charger" shares a word with "Phone" and is
        // exactly the answer.
        assertThat(FindCompanions.sameKind(named("Phone charger"), "Phone")).isFalse();
        assertThat(FindCompanions.sameKind(named("Old phone"), "Phone")).isTrue();
        assertThat(FindCompanions.sameKind(named("Spare chargers"), "USB charger")).isTrue();
    }

    @Test
    void anItemWithNoNameAsksNothing() {
        // No name is barely an identification, and a counterpart of nothing in
        // particular is a guess the model would be happy to make.
        Item nameless = new Item(null, "some cable", null, "other", 1, 0.9, List.of(), List.of(), null, null, List.of());

        FindCompanions.Result result = companions.execute(nameless, LAB, new SlotId("A1"));

        assertThat(result.hits()).isEmpty();
        assertThat(expander.calls).isEmpty();
    }

    @Test
    void theSameNameIsAskedAboutOnce() {
        expander.pairsWith("charger");

        companions.execute(named("Phone"), LAB, new SlotId("A1"));
        companions.execute(named("phone "), CELLAR, new SlotId("3"));

        assertThat(expander.calls).containsExactly("Phone");
    }

    @Test
    void oneItemFoundByTwoTermsIsListedOnce() {
        expander.pairsWith("charger", "usb-c");
        SearchHit same = hit(CELLAR, "3", "USB-C charger", 3);
        index.hits("charger", same);
        index.hits("usb-c", same);

        FindCompanions.Result result = companions.execute(named("Phone"), LAB, new SlotId("A1"));

        assertThat(result.hits()).hasSize(1);
    }

    private static Item named(String name) {
        return new Item(name, "", null, "electronics", 1, 0.9, List.of(), List.of(), null, null, List.of());
    }

    private static SearchHit hit(ContainerId container, String slot, String name, double score) {
        return new SearchHit(container, new SlotId(slot), 0, named(name), score, null);
    }

    private static final class FakeExpander implements QueryExpander {
        private final List<String> calls = new ArrayList<>();
        private List<String> counterparts = List.of();

        void pairsWith(String... names) {
            this.counterparts = List.of(names);
        }

        @Override
        public List<String> expand(String query, Collection<String> vocabulary) {
            return List.of();
        }

        @Override
        public List<String> goesWith(String name, Collection<String> vocabulary) {
            calls.add(name);
            return counterparts;
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
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }

        @Override
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public Set<String> documentsInUse() {
            return Set.of();
        }
    }
}
