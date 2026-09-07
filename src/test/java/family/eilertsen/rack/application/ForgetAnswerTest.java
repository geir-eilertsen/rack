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

class ForgetAnswerTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId B7 = new SlotId("B7");
    private static final Instant LOOKED = Instant.parse("2026-08-01T10:00:00Z");

    private FakeIndex index;
    private ForgetAnswer forget;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        forget = new ForgetAnswer(index);
    }

    @Test
    void dropsTheOneExchangeAndKeepsTheOthersInOrder() {
        holds(asked("BC547", qa("is this NPN?", "yes"), qa("max current?", "it is rated 5A"), qa("pinout?", "CBE")));

        Slot after = forget.execute(RACK, B7, 0, 1);

        assertThat(after.items().get(0).qa()).extracting(Item.QA::question)
            .containsExactly("is this NPN?", "pinout?");
    }

    @Test
    void leavesTheRestOfTheItemAlone() {
        holds(asked("BC547", qa("is this NPN?", "yes")));

        Item after = forget.execute(RACK, B7, 0, 0).items().get(0);

        assertThat(after.qa()).isEmpty();
        assertThat(after.name()).isEqualTo("BC547");
        assertThat(after.qtyEstimate()).isEqualTo(10);
        assertThat(after.sourcePhoto()).isEqualTo("one.jpg");
        assertThat(after.tags()).containsExactly("npn");
    }

    @Test
    void doesNotClaimAnybodyLookedInTheDrawer() {
        holds(asked("BC547", qa("is this NPN?", "yes")));

        assertThat(forget.execute(RACK, B7, 0, 0).lastVerified()).isEqualTo(LOOKED);
    }

    @Test
    void refusesAnExchangeThatIsNotThere() {
        holds(asked("BC547", qa("is this NPN?", "yes")));

        assertThatThrownBy(() -> forget.execute(RACK, B7, 0, 1))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void refusesOnAnItemNeverAsked() {
        // Items filed before Q&A existed carry no list at all.
        Item never = new Item("BC547", "npn", null, "semiconductor", 10, 0.9, List.of(), null, "one.jpg", null, List.of());
        holds(never);

        assertThatThrownBy(() -> forget.execute(RACK, B7, 0, 0))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void refusesARowThatIsNotThere() {
        holds(asked("BC547", qa("is this NPN?", "yes")));

        assertThatThrownBy(() -> forget.execute(RACK, B7, 3, 0))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    private void holds(Item... items) {
        index.save(RACK, new Slot(B7, List.of(items), LOOKED, null));
    }

    private static Item.QA qa(String q, String a) {
        return new Item.QA(q, a, Instant.EPOCH);
    }

    private static Item asked(String name, Item.QA... qa) {
        return new Item(name, "in the drawer", null, "semiconductor", 10, 0.9, List.of("npn"),
            List.of(qa), "one.jpg", List.of("one.jpg"), List.of());
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
        public void forget(ContainerId container) {
            slots.remove(container);
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
            return Set.of();
        }
    }
}
