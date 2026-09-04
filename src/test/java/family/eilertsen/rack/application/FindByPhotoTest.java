package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindByPhotoTest {

    private static final ContainerId LAB = new ContainerId("lab");

    private FakeExtractor extractor;
    private FakeIndex index;
    private FakeExpander expander;
    private FindByPhoto findByPhoto;

    @BeforeEach
    void setUp() {
        extractor = new FakeExtractor();
        index = new FakeIndex();
        expander = new FakeExpander();
        findByPhoto = new FindByPhoto(extractor, new FindItems(index, expander));
    }

    @Test
    void findsEveryThingInTheFrameInOneResult() {
        extractor.returns(item("Electrical tape"), item("Precision tweezers"));
        index.hits("electrical tape", hit("1", 0, 9));
        index.hits("precision tweezers", hit("2", 3, 7));

        FindItems.Result result = findByPhoto.execute(List.of(new byte[]{1}));

        assertThat(result.hits()).extracting(SearchHit::slot)
            .containsExactly(new SlotId("1"), new SlotId("2"));
        assertThat(result.query()).isEqualTo("Electrical tape, Precision tweezers");
    }

    @Test
    void oneDrawerAnsweringForTwoThingsInTheFrameIsOneRow() {
        extractor.returns(item("Electrical tape"), item("Insulating tape"));
        index.hits("electrical tape", hit("1", 0, 9));
        index.hits("insulating tape", hit("1", 0, 4));

        FindItems.Result result = findByPhoto.execute(List.of(new byte[]{1}));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).score()).isEqualTo(9.0);
    }

    @Test
    void widensAPhotographedNameTheRackFiledUnderAnotherWord() {
        // The vision model's wording need not match the drawer's, and the
        // photo is the one search where you cannot retype a better guess.
        extractor.returns(item("Insulating tape"));
        index.hits("electrical tape", hit("1", 0, 9));
        expander.returns("electrical tape");

        FindItems.Result result = findByPhoto.execute(List.of(new byte[]{1}));

        assertThat(result.expandedTerms()).containsExactly("electrical tape");
        assertThat(result.hits()).extracting(SearchHit::slot).containsExactly(new SlotId("1"));
    }

    @Test
    void refusesAnEmptyBatch() {
        assertThatThrownBy(() -> findByPhoto.execute(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one photo");
    }

    private static Item item(String name) {
        return new Item(name, "seen in the photo", null, "other", 1, 0.9, List.of(), List.of(), null, null, List.of());
    }

    private static SearchHit hit(String slot, int index, double score) {
        Item stored = new Item("stored", "on the shelf", null, "other", 1, 0.9, List.of(), List.of(), null, null, List.of());
        return new SearchHit(LAB, new SlotId(slot), index, stored, score, null);
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
        private List<String> terms = List.of();

        void returns(String... expanded) {
            this.terms = List.of(expanded);
        }

        @Override
        public List<String> expand(String query, Collection<String> vocabulary) {
            return terms;
        }

        @Override
        public List<String> goesWith(String name, Collection<String> vocabulary) {
            return List.of();
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
