package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MergeItemsTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId A4 = new SlotId("A4");

    private FakeIndex index;
    private MergeItems merge;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        merge = new MergeItems(index);
    }

    @Test
    void addsTheQuantitiesAndKeepsTheSurvivorsWording() {
        holds(battery("AAA batteries", 2, "one.jpg"), battery("More AAA", 4, "two.jpg"));

        Slot after = merge.execute(RACK, A4, 0, 1);

        assertThat(after.items()).hasSize(1);
        assertThat(after.items().get(0).name()).isEqualTo("AAA batteries");
        assertThat(after.items().get(0).qtyEstimate()).isEqualTo(6);
    }

    @Test
    void addsThePhotographsTogetherToo() {
        // Each row was seen in its own frames and the merged row was seen in
        // all of them. Dropping the other's frames would throw away the
        // evidence for the stock it brought.
        holds(battery("AAA batteries", 2, "one.jpg"), battery("More AAA", 4, "two.jpg"));

        Slot after = merge.execute(RACK, A4, 0, 1);

        assertThat(after.items().get(0).seenIn()).containsExactly("one.jpg", "two.jpg");
        assertThat(after.items().get(0).sourcePhoto()).isEqualTo("one.jpg");
    }

    @Test
    void aFrameSharedByBothIsListedOnce() {
        holds(battery("AAA batteries", 2, "one.jpg"), battery("More AAA", 4, "one.jpg"));

        assertThat(merge.execute(RACK, A4, 0, 1).items().get(0).seenIn()).containsExactly("one.jpg");
    }

    @Test
    void aRowFromBeforeFramesWereRecordedStillContributesTheOneItWasReadFrom() {
        Item old = new Item("AAA batteries", "older row", null, "other", 2, 0.9,
            List.of(), null, List.of(), "old.jpg", null);
        holds(old, battery("More AAA", 4, "two.jpg"));

        assertThat(merge.execute(RACK, A4, 0, 1).items().get(0).seenIn())
            .containsExactly("old.jpg", "two.jpg");
    }

    @Test
    void anItemNeitherRowHasAFrameForKeepsNone() {
        Item a = new Item("AAA", "a", null, "other", 2, 0.9, List.of(), null, List.of(), null, null);
        Item b = new Item("AAA", "b", null, "other", 4, 0.9, List.of(), null, List.of(), null, null);
        holds(a, b);

        assertThat(merge.execute(RACK, A4, 0, 1).items().get(0).seenIn()).isNull();
    }

    @Test
    void keepsTheSurvivorsQuestionsAndAnswers() {
        Item asked = new Item("AAA batteries", "with history", null, "other", 2, 0.9, List.of("aaa"), null,
            List.of(new Item.QA("are these alkaline?", "no, zinc-carbon", Instant.EPOCH)), "one.jpg", List.of("one.jpg"));
        holds(asked, battery("More AAA", 4, "two.jpg"));

        assertThat(merge.execute(RACK, A4, 0, 1).items().get(0).qa()).hasSize(1);
    }

    @Test
    void theOtherRowIsGoneAndTheRestAreUndisturbed() {
        holds(battery("AAA batteries", 2, "one.jpg"), battery("Screws", 10, "two.jpg"),
            battery("More AAA", 4, "three.jpg"));

        Slot after = merge.execute(RACK, A4, 0, 2);

        assertThat(after.items()).extracting(Item::name).containsExactly("AAA batteries", "Screws");
    }

    @Test
    void mergingRecordsThatSomeoneLookedInTheDrawer() {
        holds(battery("AAA batteries", 2, "one.jpg"), battery("More AAA", 4, "two.jpg"));

        assertThat(merge.execute(RACK, A4, 0, 1).lastVerified()).isAfter(Instant.EPOCH);
    }

    @Test
    void refusesToMergeAnItemIntoItself() {
        holds(battery("AAA batteries", 2, "one.jpg"));

        assertThatThrownBy(() -> merge.execute(RACK, A4, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("into itself");
    }

    @Test
    void refusesARowThatIsNotThere() {
        holds(battery("AAA batteries", 2, "one.jpg"));

        assertThatThrownBy(() -> merge.execute(RACK, A4, 0, 4))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    private void holds(Item... items) {
        index.save(RACK, new Slot(A4, List.of(items), Instant.EPOCH, List.of("one.jpg", "two.jpg"), null));
    }

    private static Item battery(String name, int qty, String frame) {
        return new Item(name, "in the drawer", null, "other", qty, 0.9, List.of(), null,
            List.of(), frame, List.of(frame));
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.ofNullable(slots.getOrDefault(container, Map.of()).get(slot));
        }

        @Override
        public void save(ContainerId container, Slot slot) {
            slots.computeIfAbsent(container, k -> new LinkedHashMap<>()).put(slot.id(), slot);
        }

        @Override
        public Collection<Slot> all(ContainerId container) {
            return List.copyOf(slots.getOrDefault(container, Map.of()).values());
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return List.of();
        }

        @Override
        public List<SearchHit> searchBySimilarity(float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
