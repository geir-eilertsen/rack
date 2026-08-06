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
import java.util.Set;

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
        store = new FakeStore(List.of(new Container(BIN, "Small bin", ContainerLayout.linear(3, "b"), 0.4f, "drawer")));
        index = new FakeIndex();
        registry = new ContainerRegistry(store);
        update = new UpdateContainer(registry);
        delete = new DeleteContainer(registry, index, new ForgetUnusedPhotos(index, new NoImages()));
    }

    @Test
    void renamesWithoutTouchingSlots() {
        Container before = registry.get(BIN).orElseThrow();

        Container after = update.execute(BIN, new UpdateContainer.Fields("Resistor bin", null, null));

        assertThat(after.name()).isEqualTo("Resistor bin");
        assertThat(after.slots()).isEqualTo(before.slots());
        assertThat(after.labelScale()).isEqualTo(before.labelScale());
        assertThat(store.saved).singleElement().isEqualTo(after);
    }

    @Test
    void changesLabelScaleWithoutTouchingName() {
        Container after = update.execute(BIN, new UpdateContainer.Fields(null, 1.0f, null));

        assertThat(after.labelScale()).isEqualTo(1.0f);
        assertThat(after.name()).isEqualTo("Small bin");
    }

    @Test
    void rejectsBlankNameAndOutOfRangeScale() {
        assertThatThrownBy(() -> update.execute(BIN, new UpdateContainer.Fields("  ", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");

        assertThatThrownBy(() -> update.execute(BIN, new UpdateContainer.Fields(null, 0f, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("labelScale");

        assertThatThrownBy(() -> update.execute(BIN, new UpdateContainer.Fields(null, 2.5f, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("labelScale");

        assertThat(registry.get(BIN).orElseThrow().name()).isEqualTo("Small bin");
    }

    @Test
    void updatingAnUnknownContainerFails() {
        assertThatThrownBy(() -> update.execute(new ContainerId("nope"), new UpdateContainer.Fields("x", null, null)))
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
        index.put(BIN, new Slot(new SlotId("b1"), List.of(), null, Instant.now()));

        delete.execute(BIN);

        assertThat(registry.get(BIN)).isEmpty();
    }

    @Test
    void aSlotEmptiedOfItemsHasNoPhotographsLeftToTrapIt() {
        // This container used to be undeletable. A slot kept its own list of
        // photographs, so emptying it of items left the list behind, that list
        // counted as content, and there was nothing on screen to remove. Items
        // own their photographs now, which makes the state that trapped it
        // unrepresentable rather than merely allowed.
        Item photographed = new Item("BC547", "on a reel", null, null, null, 0.9,
            List.of(), null, List.of(), "2026-08-04-1712.jpg", List.of("2026-08-04-1712.jpg"));
        index.put(BIN, new Slot(new SlotId("b1"), List.of(photographed), null, null));
        assertThat(index.get(BIN, new SlotId("b1")).orElseThrow().frames())
            .containsExactly("2026-08-04-1712.jpg");

        index.put(BIN, new Slot(new SlotId("b1"), List.of(), null, null));

        assertThat(index.get(BIN, new SlotId("b1")).orElseThrow().frames()).isEmpty();
        delete.execute(BIN);
        assertThat(registry.get(BIN)).isEmpty();
    }

    @Test
    void stillRefusesWhenASlotHoldsAnItem() {
        index.put(BIN, new Slot(new SlotId("b1"), List.of(item("BC547")), null, null));

        assertThatThrownBy(() -> delete.execute(BIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("still holds items")
            .hasMessageContaining("b1");

        assertThat(registry.get(BIN)).isPresent();
    }

    @Test
    void refusesToDeleteAContainerHoldingItems() {
        index.put(BIN, new Slot(new SlotId("b3"), List.of(item("BC547")), null, null));
        index.put(BIN, new Slot(new SlotId("b1"), List.of(item("M4 bolt")), null, null));

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
        store.saveAll(List.of(new Container(shelf, "Shelf", ContainerLayout.linear(12, ""), 1.0f, "drawer")));
        DeleteContainer deleteShelf = new DeleteContainer(new ContainerRegistry(store), index,
            new ForgetUnusedPhotos(index, new NoImages()));
        index.put(shelf, new Slot(new SlotId("11"), List.of(item("a")), null, null));
        index.put(shelf, new Slot(new SlotId("2"), List.of(item("b")), null, null));
        index.put(shelf, new Slot(new SlotId("1"), List.of(item("c")), null, null));

        assertThatThrownBy(() -> deleteShelf.execute(shelf))
            .hasMessageContaining("(1, 2, 11)");
    }

    @Test
    void refusesToDeleteWhenAnItemSitsInASlotOutsideTheLayout() {
        index.put(BIN, new Slot(new SlotId("b9"), List.of(item("stray")), null, null));

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
        return new Item(description, description, null, null, null, 0.9, List.of(), null, List.of(), null, null);
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

        @Override
        public void forget(ContainerId container) {
            slots.remove(container);
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
    @Test
    void deletingAContainerTakesItsSlotStateWithIt() {
        // Leaving it behind left a folder indistinguishable from a live
        // container, and photo references nothing could reach — keeping frames
        // alive for something that no longer existed.
        index.put(BIN, new Slot(new SlotId("b1"), List.of(), null, Instant.now()));

        delete.execute(BIN);

        assertThat(index.all(BIN)).isEmpty();
    }

    /** The sweep needs a store; nothing here is about photographs on disk. */
    private static final class NoImages implements family.eilertsen.rack.domain.port.ImageStore {
        @Override
        public String store(byte[] image, String contentType) {
            return "x.jpg";
        }

        @Override
        public List<String> all() {
            return List.of();
        }

        @Override
        public byte[] read(String filename) {
            return new byte[0];
        }

        @Override
        public void delete(String filename) {
        }
    }

}
