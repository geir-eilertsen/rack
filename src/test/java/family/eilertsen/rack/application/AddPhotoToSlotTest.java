package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartExtractor;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddPhotoToSlotTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId A1 = new SlotId("A1");

    private FakeImages images;
    private FakeExtractor extractor;
    private FakeIndex index;
    private AddPhotoToSlot addPhoto;

    @BeforeEach
    void setUp() {
        images = new FakeImages();
        extractor = new FakeExtractor();
        index = new FakeIndex();
        addPhoto = new AddPhotoToSlot(images, extractor, index);
    }

    @Test
    void extractsTheWholeBatchInOneCall() {
        extractor.returns(new Extraction(item("bag of M4 bolts"), List.of(0, 1, 2)));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("front"), photo("label"), photo("side")));

        assertThat(extractor.calls).hasSize(1);
        assertThat(extractor.calls.get(0)).containsExactly("front", "label", "side");
        assertThat(result.photoFilenames()).containsExactly("front.jpg", "label.jpg", "side.jpg");
        assertThat(index.get(RACK, A1).orElseThrow().frames())
            .isEqualTo(result.photoFilenames());
    }

    @Test
    void aFrameNothingWasReadFromIsNotKept() {
        // Items own photographs, so a frame the extraction attributed nothing to
        // has nothing to hang off. The model was looking straight at it when it
        // found nothing there, which makes dropping it a reading of the picture
        // rather than a guess about it — and the count comes back so the user can
        // shoot it again.
        extractor.returns(new Extraction(item("bag of M4 bolts"), 0));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("front"), photo("blurry")));

        assertThat(result.photoFilenames()).containsExactly("front.jpg");
        assertThat(result.discarded()).isEqualTo(1);
        assertThat(images.stored).containsExactly("front.jpg");
        assertThat(index.get(RACK, A1).orElseThrow().frames()).containsExactly("front.jpg");
    }

    @Test
    void aBatchNothingWasReadFromAtAllLeavesNoFilesBehind() {
        extractor.returns();

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        assertThat(result.extracted()).isEmpty();
        assertThat(result.discarded()).isEqualTo(2);
        assertThat(images.stored).isEmpty();
    }

    @Test
    void oneItemSeenAcrossTwoFramesIsIndexedOnce() {
        // The whole point of the single call: the model merges the front shot and
        // the label shot into one item, and it lands in the index as one item.
        extractor.returns(new Extraction(item("bag of M4 bolts, DIN 933"), List.of(0, 1)));

        addPhoto.execute(RACK, A1, List.of(photo("front"), photo("label")));

        Slot saved = index.get(RACK, A1).orElseThrow();
        assertThat(saved.items()).hasSize(1);
        assertThat(saved.frames()).containsExactly("front.jpg", "label.jpg");
    }

    @Test
    void attributesEachItemToThePhotoThatShowsIt() {
        extractor.returns(
            new Extraction(item("BC547"), 2),
            new Extraction(item("M4 bolts"), 0));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b"), photo("c")));

        assertThat(result.extracted()).extracting(Item::sourcePhoto)
            .containsExactly("c.jpg", "a.jpg");
        // Nothing was read from b, so it is not kept.
        assertThat(result.photoFilenames()).containsExactly("a.jpg", "c.jpg");
    }

    @Test
    void anItemKeepsEveryFrameItWasSeenIn() {
        // The whole point of the single call: the front shot, the side shot and
        // the label shot are one item — and now the item can name all three.
        extractor.returns(new Extraction(item("bag of M4 bolts"), List.of(2, 0)));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("front"), photo("side"), photo("label")));

        Item filed = result.extracted().get(0);
        assertThat(filed.seenIn()).containsExactly("label.jpg", "front.jpg");
        // The first frame stays the thumbnail, so nothing about the row changes.
        assertThat(filed.sourcePhoto()).isEqualTo("label.jpg");
        // The side shot showed nothing nameable, so it does not survive the batch.
        assertThat(result.discarded()).isEqualTo(1);
    }

    @Test
    void dropsFramesTheModelInventedButKeepsTheRest() {
        extractor.returns(new Extraction(item("bolts"), List.of(1, 9, -1, 1)));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        assertThat(result.extracted().get(0).seenIn()).containsExactly("b.jpg");
    }

    @Test
    void anImageIndexOutsideTheBatchFallsBackToTheFirstPhoto() {
        extractor.returns(new Extraction(item("stray"), 7), new Extraction(item("negative"), -1));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        assertThat(result.extracted()).extracting(Item::sourcePhoto).containsOnly("a.jpg");
    }

    @Test
    void appendsToWhatTheSlotAlreadyHeld() {
        Item held = new Item("old", "old", null, null, 1, 0.9, List.of(), null, List.of(),
            "earlier.jpg", List.of("earlier.jpg"));
        index.save(RACK, new Slot(A1, List.of(held), Instant.EPOCH, Instant.EPOCH));
        extractor.returns(new Extraction(item("new one"), 0), new Extraction(item("new two"), 1));

        addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        Slot saved = index.get(RACK, A1).orElseThrow();
        assertThat(saved.items()).extracting(Item::description).containsExactly("old", "new one", "new two");
        // The frames the drawer already had are not the batch's to touch.
        assertThat(saved.frames()).containsExactly("earlier.jpg", "a.jpg", "b.jpg");
        assertThat(saved.lastVerified()).isAfter(Instant.EPOCH);
        assertThat(saved.printedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void refusesAnEmptyBatch() {
        assertThatThrownBy(() -> addPhoto.execute(RACK, A1, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one photo");

        assertThat(images.written).isZero();
        assertThat(extractor.calls).isEmpty();
    }

    private static AddPhotoToSlot.Photo photo(String marker) {
        return new AddPhotoToSlot.Photo(marker.getBytes(StandardCharsets.UTF_8), "image/jpeg");
    }

    private static Item item(String description) {
        return new Item(description, description, null, null, 1, 0.9, List.of(), null, List.of(), null, null);
    }

    private static final class FakeImages implements ImageStore {
        /** Filenames, so that a delete is visible here and not only in the index. */
        private final List<String> stored = new ArrayList<>();
        private int written;

        @Override
        public String store(byte[] image, String contentType) {
            String marker = new String(image, StandardCharsets.UTF_8);
            written++;
            String name = marker + ".jpg";
            stored.add(name);
            return name;
        }

        @Override
        public List<String> all() {
            return List.copyOf(stored);
        }

        @Override
        public byte[] read(String filename) {
            return new byte[0];
        }


        @Override
        public void delete(String filename) {
            stored.remove(filename);
        }
    }

    private static final class FakeExtractor implements PartExtractor {
        private final List<List<String>> calls = new ArrayList<>();
        private List<Extraction> result = List.of();

        void returns(Extraction... extractions) {
            this.result = List.of(extractions);
        }

        @Override
        public List<Extraction> extract(List<byte[]> images) {
            calls.add(images.stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList());
            return result;
        }
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
        public void forget(ContainerId container) {
            slots.remove(container);
        }

        @Override
        public Set<String> photosInUse() {
            Set<String> used = new java.util.LinkedHashSet<>();
            for (Map<SlotId, Slot> byId : slots.values()) {
                for (Slot s : byId.values()) used.addAll(s.frames());
            }
            return used;
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }
    }

}
