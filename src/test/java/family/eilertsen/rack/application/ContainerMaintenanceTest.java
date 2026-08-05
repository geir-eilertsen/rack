package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerMaintenanceTest {

    private static final ContainerId BIN = new ContainerId("bin");

    private FakeStore store;
    private FakeIndex index;
    private ContainerRegistry registry;
    private UpdateContainer update;
    private DeleteContainer delete;

    @BeforeEach
    void setUp() {
        store = new FakeStore(List.of(new Container(BIN, "Small bin", ContainerLayout.linear(3, "b"), 0.4f)));
        index = new FakeIndex();
        registry = new ContainerRegistry(store);
        update = new UpdateContainer(registry);
        delete = new DeleteContainer(registry, index);
    }

    @Test
    void renamesWithoutTouchingSlots() {
        Container before = registry.get(BIN).orElseThrow();

        Container after = update.execute(BIN, new UpdateContainer.Fields("Resistor bin", null));

        assertThat(after.name()).isEqualTo("Resistor bin");
        assertThat(after.slots()).isEqualTo(before.slots());
        assertThat(after.labelScale()).isEqualTo(before.labelScale());
        assertThat(store.saved).singleElement().isEqualTo(after);
    }

    @Test
    void changesLabelScaleWithoutTouchingName() {
        Container after = update.execute(BIN, new UpdateContainer.Fields(null, 1.0f));

        assertThat(after.labelScale()).isEqualTo(1.0f);
        assertThat(after.name()).isEqualTo("Small bin");
    }

    @Test
    void rejectsBlankNameAndOutOfRangeScale() {
        assertThatThrownBy(() -> update.execute(BIN, new UpdateContainer.Fields("  ", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");

        assertThatThrownBy(() -> update.execute(BIN, new UpdateContainer.Fields(null, 0f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("labelScale");

        assertThatThrownBy(() -> update.execute(BIN, new UpdateContainer.Fields(null, 2.5f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("labelScale");

        assertThat(registry.get(BIN).orElseThrow().name()).isEqualTo("Small bin");
    }

    @Test
    void updatingAnUnknownContainerFails() {
        assertThatThrownBy(() -> update.execute(new ContainerId("nope"), new UpdateContainer.Fields("x", null)))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deletesAnEmptyContainer() {
        delete.execute(BIN);

        assertThat(registry.get(BIN)).isEmpty();
        assertThat(registry.all()).isEmpty();
        assertThat(store.saved).isEmpty();
    }

    @Test
    void deletesAContainerWhoseSlotsWereOnlyLabelled() {
        index.put(BIN, new Slot(new SlotId("b1"), List.of(), null, List.of(), Instant.now()));

        delete.execute(BIN);

        assertThat(registry.get(BIN)).isEmpty();
    }

    @Test
    void refusesToDeleteWhenASlotHoldsAPhotoButNoExtractedItems() {
        index.put(BIN, new Slot(new SlotId("b1"), List.of(), null, List.of("2026-08-04-1712.jpg"), null));

        assertThatThrownBy(() -> delete.execute(BIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("items or photos")
            .hasMessageContaining("b1");

        assertThat(registry.get(BIN)).isPresent();
    }

    @Test
    void refusesToDeleteAContainerHoldingItems() {
        index.put(BIN, new Slot(new SlotId("b3"), List.of(item("BC547")), null, List.of(), null));
        index.put(BIN, new Slot(new SlotId("b1"), List.of(item("M4 bolt")), null, List.of(), null));

        assertThatThrownBy(() -> delete.execute(BIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Small bin")
            .hasMessageContaining("2 slots")
            .hasMessageContaining("b1, b3");

        assertThat(registry.get(BIN)).isPresent();
    }

    @Test
    void listsOccupiedSlotsInLayoutOrderNotAlphabetically() {
        ContainerId shelf = new ContainerId("shelf");
        store.saveAll(List.of(new Container(shelf, "Shelf", ContainerLayout.linear(12, ""), 1.0f)));
        DeleteContainer deleteShelf = new DeleteContainer(new ContainerRegistry(store), index);
        index.put(shelf, new Slot(new SlotId("11"), List.of(item("a")), null, List.of(), null));
        index.put(shelf, new Slot(new SlotId("2"), List.of(item("b")), null, List.of(), null));
        index.put(shelf, new Slot(new SlotId("1"), List.of(item("c")), null, List.of(), null));

        assertThatThrownBy(() -> deleteShelf.execute(shelf))
            .hasMessageContaining("(1, 2, 11)");
    }

    @Test
    void refusesToDeleteWhenAnItemSitsInASlotOutsideTheLayout() {
        index.put(BIN, new Slot(new SlotId("b9"), List.of(item("stray")), null, List.of(), null));

        assertThatThrownBy(() -> delete.execute(BIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("b9");
    }

    @Test
    void deletingAnUnknownContainerFails() {
        assertThatThrownBy(() -> delete.execute(new ContainerId("nope")))
            .isInstanceOf(NoSuchElementException.class);
    }

    private static Item item(String description) {
        return new Item(description, description, null, null, null, 0.9, List.of(), null, List.of(), null);
    }

    private static final class FakeStore implements ContainerStore {
        private List<Container> saved;

        FakeStore(List<Container> initial) {
            this.saved = new ArrayList<>(initial);
        }

        @Override
        public List<Container> loadAll() {
            return List.copyOf(saved);
        }

        @Override
        public void saveAll(List<Container> containers) {
            this.saved = new ArrayList<>(containers);
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        void put(ContainerId container, Slot slot) {
            save(container, slot);
        }

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
    }
}
