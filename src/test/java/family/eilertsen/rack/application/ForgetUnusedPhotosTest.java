package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ForgetUnusedPhotosTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId A1 = new SlotId("A1");

    private FakeIndex index;
    private FakeImages images;
    private ForgetUnusedPhotos sweep;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        images = new FakeImages();
        sweep = new ForgetUnusedPhotos(index, images);
    }

    @Test
    void deletesAPhotographNoItemPointsAt() {
        images.holds("kept.jpg", "stray.jpg");
        index.put(RACK, new Slot(A1, List.of(shownIn("kept.jpg")), null, null));

        assertThat(sweep.sweep()).containsExactly("stray.jpg");
        assertThat(images.all()).containsExactly("kept.jpg");
    }

    @Test
    void aFrameNoItemNamesIsNotEvidenceOfAnythingAndGoes() {
        // This used to be kept, on the grounds that a frame nothing was read
        // from is the evidence the extraction missed something. In practice it
        // produced pictures no page rendered and a container that refused to be
        // deleted, so the frame outlived only itself. Items are the physical
        // things; a photograph with no item is a photograph of nothing anyone has.
        images.holds("evidence.jpg");
        index.put(RACK, new Slot(A1, List.of(), null, null));

        assertThat(sweep.sweep()).containsExactly("evidence.jpg");
        assertThat(images.all()).isEmpty();
    }

    @Test
    void keepsAFrameAMovedItemCarriedIntoAnotherDrawer() {
        // The reference travels with the item, which is the whole point of
        // hanging it there: the old drawer has no say in it.
        images.holds("carried.jpg");
        index.put(RACK, new Slot(A1, List.of(), null, null));
        index.put(new ContainerId("bin"), new Slot(new SlotId("b1"),
            List.of(shownIn("carried.jpg")), null, null));

        assertThat(sweep.sweep()).isEmpty();
        assertThat(images.all()).containsExactly("carried.jpg");
    }

    @Test
    void keepsAFrameNamedOnlyAsAnItemsSourceAndNotInItsSeenIn() {
        // Items catalogued before seen_in existed have a source and nothing else.
        images.holds("older.jpg");
        Item old = new Item("bolts", "from before", null, "fastener", 5, 0.9,
            List.of(), List.of(), "older.jpg", null, List.of());
        index.put(RACK, new Slot(A1, List.of(old), null, null));

        assertThat(sweep.sweep()).isEmpty();
    }

    private static Item shownIn(String... frames) {
        return new Item("bolts", "in a bag", null, "fastener", 5, 0.9,
            List.of(), List.of(), frames[0], List.of(frames), List.of());
    }

    @Test
    void anEmptyRackKeepsNothingAndBreaksNothing() {
        images.holds("a.jpg", "b.jpg");

        assertThat(sweep.sweep()).containsExactly("a.jpg", "b.jpg");
        assertThat(images.all()).isEmpty();
    }

    private static final class FakeImages implements ImageStore {
        @Override
        public byte[] thumbnail(String filename, int maxEdge) {
            return read(filename);
        }

        private final List<String> files = new ArrayList<>();

        void holds(String... names) {
            files.addAll(List.of(names));
        }

        @Override
        public String store(byte[] image, String contentType) {
            String name = "stored-" + files.size() + ".jpg";
            files.add(name);
            return name;
        }

        @Override
        public List<String> all() {
            return List.copyOf(files);
        }

        @Override
        public byte[] read(String filename) {
            return new byte[0];
        }

        @Override
        public void delete(String filename) {
            files.remove(filename);
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        void put(ContainerId container, Slot slot) {
            slots.computeIfAbsent(container, k -> new LinkedHashMap<>()).put(slot.id(), slot);
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
            Set<String> used = new LinkedHashSet<>();
            for (Map<SlotId, Slot> byId : slots.values()) {
                for (Slot s : byId.values()) used.addAll(s.frames());
            }
            return used;
        }

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.ofNullable(slots.getOrDefault(container, Map.of()).get(slot));
        }

        @Override
        public void save(ContainerId container, Slot slot) {
            put(container, slot);
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
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
