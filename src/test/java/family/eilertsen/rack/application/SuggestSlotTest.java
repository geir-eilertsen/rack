package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Claim;
import family.eilertsen.rack.domain.model.Companion;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.PairFinder;
import family.eilertsen.rack.domain.port.PartExtractor;
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

class SuggestSlotTest {

    private static final ContainerId LAB = new ContainerId("lab");

    private FakeExtractor extractor;
    private FakeIndex index;
    private FakeExpander expander;
    private FakeFinder finder;
    private SuggestSlot suggest;

    @BeforeEach
    void setUp() {
        extractor = new FakeExtractor();
        index = new FakeIndex();
        expander = new FakeExpander();
        finder = new FakeFinder();
        ContainerRegistry registry = new ContainerRegistry(new FakeStore(List.of(
            new Container(LAB, "Lab", ContainerLayout.linear(12, null), 1.0f, "shelf", null, null))));
        suggest = new SuggestSlot(extractor, new FindItems(index, expander), new FindCompanions(index, registry, finder));
    }

    @Test
    void saysWhichItemThePhotographedThingBelongsWithAndWhere() {
        // A charger filed by likeness goes in with the other chargers, and the
        // phone it belongs to stays where it was — filing is where a pair gets
        // split, so the phone's drawer is offered alongside, with the reason.
        extractor.returns(item("Phone charger", null, List.of()));
        index.hits("phone charger", hit("1", 9));
        index.put(LAB, new Slot(new SlotId("7"), List.of(
            new Item("Phone", "old Android", null, "electronics", 1, 0.9, List.of(), List.of(), null, null, List.of())), null, null));
        finder.cites(new Companion(0, "lab/7#0", "charges it"));

        SuggestSlot.Result result = suggest.execute(List.of(new byte[]{1}));

        assertThat(result.suggestions()).extracting(SuggestSlot.Suggestion::slot).containsExactly(new SlotId("1"));
        assertThat(result.companions()).hasSize(1);
        assertThat(result.companions().get(0).hits()).extracting(FindCompanions.Found::slot).containsExactly(new SlotId("7"));
        assertThat(result.companions().get(0).hits().get(0).why()).isEqualTo("charges it");
    }

    @Test
    void suggestsTheSlotHoldingTheSameThingUnderAnotherName() {
        // The drawer says "Electrical tape"; the extractor called this roll
        // something else. Without widening the roll gets filed a second time.
        extractor.returns(item("Insulating tape", null, List.of()));
        index.hits("electrical tape", hit("1", 9));
        expander.returns("electrical tape");

        SuggestSlot.Result result = suggest.execute(List.of(new byte[]{1}));

        assertThat(result.suggestions()).extracting(SuggestSlot.Suggestion::slot)
            .containsExactly(new SlotId("1"));
    }

    @Test
    void searchesTheNameAndNotJustThePartNumberAndTags() {
        extractor.returns(item("Electrical tape", null, List.of()));
        index.hits("electrical tape", hit("1", 9));

        SuggestSlot.Result result = suggest.execute(List.of(new byte[]{1}));

        assertThat(result.suggestions()).extracting(SuggestSlot.Suggestion::slot)
            .containsExactly(new SlotId("1"));
        // The name landed on its own, so nothing had to be widened.
        assertThat(expander.calls).isEmpty();
    }

    @Test
    void aPartNumberOrTagNeverCostsAModelCall() {
        // Both are already precise — tags are the extractor's own synonyms — so
        // a batch is capped at one call per item however many tags it carries.
        extractor.returns(item(null, "BC547", List.of("TO-92", "through hole")));

        suggest.execute(List.of(new byte[]{1}));

        assertThat(expander.calls).isEmpty();
    }

    @Test
    void aSlotOnlyAGenericTagFoundIsNotSuggested() {
        // Slot 4 is the resistor drawer: twenty-two of them come on tape reels,
        // so the one-word tag "tape" matches it and nothing else does. A tag is a
        // single word, so requiring every word to match can't discipline it.
        extractor.returns(item("Electrical tape", "3M-1712", List.of("tape")));
        index.hits("electrical tape", hit("1", 9));
        index.hits("3m-1712", hit("1", 3));
        index.hits("tape", hit("1", 6), hit("4", 2));

        SuggestSlot.Result result = suggest.execute(List.of(new byte[]{1}));

        assertThat(result.suggestions()).extracting(SuggestSlot.Suggestion::slot)
            .containsExactly(new SlotId("1"));
    }

    @Test
    void aTagStillRaisesASlotTheNameAlreadyFound() {
        extractor.returns(item("Electrical tape", null, List.of("tape")));
        index.hits("electrical tape", hit("1", 9));
        index.hits("tape", hit("1", 6));

        SuggestSlot.Result result = suggest.execute(List.of(new byte[]{1}));

        assertThat(result.suggestions().get(0).score()).isEqualTo(15.0);
    }

    @Test
    void anItemTooVagueToAnchorSuggestsNothing() {
        // No name and no part number is barely an identification at all, and a
        // slot picked from its tags alone is exactly the noise being removed.
        extractor.returns(item(null, null, List.of("tape")));
        index.hits("tape", hit("4", 2));

        assertThat(suggest.execute(List.of(new byte[]{1})).suggestions()).isEmpty();
    }

    @Test
    void theSameItemFoundByTwoTermsIsListedOnce() {
        extractor.returns(item("Electrical tape", null, List.of("tape")));
        SearchHit sameItem = hit("1", 6);
        index.hits("electrical tape", sameItem);
        index.hits("tape", sameItem);

        SuggestSlot.Result result = suggest.execute(List.of(new byte[]{1}));

        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).matches()).hasSize(1);
    }

    private static Item item(String name, String partNumber, List<String> tags) {
        return new Item(name, "a roll of something", partNumber, "other", 1, 0.9, tags, List.of(), null, null, List.of());
    }

    private static SearchHit hit(String slot, double score) {
        Item stored = new Item("Electrical tape", "black roll", null, "other", 1, 0.9, List.of(), List.of(), null, null, List.of());
        return new SearchHit(LAB, new SlotId(slot), 0, stored, score, null);
    }

    private static final class FakeExtractor implements PartExtractor {
        private List<Extraction> result = List.of();

        void returns(Item... items) {
            List<Extraction> extractions = new ArrayList<>();
            for (Item item : items) extractions.add(new Extraction(item, 0));
            this.result = List.copyOf(extractions);
        }

        @Override
        public List<Extraction> extract(List<byte[]> images) {
            return result;
        }
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

    private static final class FakeFinder implements PairFinder {
        private List<Companion> answer = List.of();

        void cites(Companion... companions) {
            this.answer = List.of(companions);
        }

        @Override
        public List<Companion> find(List<String> subjects, List<String> listing) {
            return answer;
        }

        @Override
        public List<Boolean> confirm(List<Claim> claims) {
            List<Boolean> stands = new ArrayList<>();
            for (int i = 0; i < claims.size(); i++) stands.add(true);
            return stands;
        }
    }

    private record FakeStore(List<Container> containers) implements ContainerStore {
        @Override
        public List<Container> loadAll() {
            return containers;
        }

        @Override
        public void saveAll(List<Container> containers) {
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<String, List<SearchHit>> byQuery = new LinkedHashMap<>();
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        void put(ContainerId container, Slot slot) {
            slots.computeIfAbsent(container, k -> new LinkedHashMap<>()).put(slot.id(), slot);
        }

        void hits(String query, SearchHit... hits) {
            byQuery.put(query.toLowerCase(Locale.ROOT), List.of(hits));
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return byQuery.getOrDefault(query.toLowerCase(Locale.ROOT), List.of());
        }

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.ofNullable(slots.getOrDefault(container, Map.of()).get(slot));
        }

        @Override
        public void save(ContainerId container, Slot slot) {
        }

        @Override
        public Collection<Slot> all(ContainerId container) {
            return new ArrayList<>(slots.getOrDefault(container, Map.of()).values());
        }


        @Override
        public void forget(ContainerId container) {
            byQuery.clear();
        }

        @Override
        public Set<String> documentsInUse() {
            return Set.of();
        }

        @Override
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of("Electrical tape");
        }
    }
}
