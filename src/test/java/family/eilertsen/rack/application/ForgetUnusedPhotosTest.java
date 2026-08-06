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
    void deletesAPhotographNothingPointsAt() {
        images.holds("kept.jpg", "stray.jpg");
        index.put(RACK, new Slot(A1, List.of(), null, List.of("kept.jpg"), null));

        assertThat(sweep.sweep()).containsExactly("stray.jpg");
        assertThat(images.all()).containsExactly("kept.jpg");
    }

    @Test
    void keepsAFrameASlotNamesEvenWhenNoItemDoes() {
        // The point of keeping it: an unreferenced frame is the evidence that
        // the extraction missed something. Eighteen frames in this rack are
        // named by no item while plainly showing what is in the drawer.
        images.holds("evidence.jpg");
        index.put(RACK, new Slot(A1, List.of(), null, List.of("evidence.jpg"), null));

        assertThat(sweep.sweep()).isEmpty();
        assertThat(images.all()).containsExactly("evidence.jpg");
    }

    @Test
    void keepsAFrameOnlyAnItemNamesEvenIfItsSlotListForgotIt() {
        // A moved item carries its frames without the old drawer's photo list
        // following, so an item's own reference has to count.
        images.holds("carried.jpg");
        Item moved = new Item("bolts", "moved here", null, "fastener", 5, 0.9,
            List.of(), null, List.of(), "carried.jpg", List.of("carried.jpg"));
        index.put(RACK, new Slot(A1, List.of(moved), null, List.of(), null));

        assertThat(sweep.sweep()).isEmpty();
    }

    @Test
    void anEmptyRackKeepsNothingAndBreaksNothing() {
        images.holds("a.jpg", "b.jpg");

        assertThat(sweep.sweep()).containsExactly("a.jpg", "b.jpg");
        assertThat(images.all()).isEmpty();
    }

    private static final class FakeImages implements ImageStore {
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
        public Set<String> photosInUse() {
            Set<String> used = new LinkedHashSet<>();
            for (Map<SlotId, Slot> byId : slots.values()) {
                for (Slot s : byId.values()) {
                    if (s.photos() != null) used.addAll(s.photos());
                    for (Item i : s.items()) {
                        if (i.sourcePhoto() != null) used.add(i.sourcePhoto());
                        if (i.seenIn() != null) used.addAll(i.seenIn());
                    }
                }
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
        public List<SearchHit> searchBySimilarity(float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
