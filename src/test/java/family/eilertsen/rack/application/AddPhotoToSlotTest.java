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
    void keepsEveryPhotoButExtractsTheBatchInOneCall() {
        extractor.returns(new Extraction(item("bag of M4 bolts"), 0));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("front"), photo("label"), photo("side")));

        assertThat(images.stored).containsExactly("front", "label", "side");
        assertThat(extractor.calls).hasSize(1);
        assertThat(extractor.calls.get(0)).containsExactly("front", "label", "side");
        assertThat(result.photoFilenames()).hasSize(3);
        assertThat(index.get(RACK, A1).orElseThrow().photos()).isEqualTo(result.photoFilenames());
    }

    @Test
    void oneItemSeenAcrossTwoFramesIsIndexedOnce() {
        // The whole point of the single call: the model merges the front shot and
        // the label shot into one item, and it lands in the index as one item.
        extractor.returns(new Extraction(item("bag of M4 bolts, DIN 933"), 1));

        addPhoto.execute(RACK, A1, List.of(photo("front"), photo("label")));

        Slot saved = index.get(RACK, A1).orElseThrow();
        assertThat(saved.items()).hasSize(1);
        assertThat(saved.photos()).hasSize(2);
    }

    @Test
    void attributesEachItemToThePhotoThatShowsIt() {
        extractor.returns(
            new Extraction(item("BC547"), 2),
            new Extraction(item("M4 bolts"), 0));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b"), photo("c")));

        List<String> filenames = result.photoFilenames();
        assertThat(result.extracted()).extracting(Item::sourcePhoto)
            .containsExactly(filenames.get(2), filenames.get(0));
    }

    @Test
    void anItemKeepsEveryFrameItWasSeenIn() {
        // The whole point of the single call: the front shot, the side shot and
        // the label shot are one item — and now the item can name all three.
        extractor.returns(new Extraction(item("bag of M4 bolts"), List.of(2, 0)));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("front"), photo("side"), photo("label")));

        List<String> filenames = result.photoFilenames();
        Item filed = result.extracted().get(0);
        assertThat(filed.seenIn()).containsExactly(filenames.get(2), filenames.get(0));
        // The first frame stays the thumbnail, so nothing about the row changes.
        assertThat(filed.sourcePhoto()).isEqualTo(filenames.get(2));
    }

    @Test
    void dropsFramesTheModelInventedButKeepsTheRest() {
        extractor.returns(new Extraction(item("bolts"), List.of(1, 9, -1, 1)));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        assertThat(result.extracted().get(0).seenIn()).containsExactly(result.photoFilenames().get(1));
    }

    @Test
    void anImageIndexOutsideTheBatchFallsBackToTheFirstPhoto() {
        extractor.returns(new Extraction(item("stray"), 7), new Extraction(item("negative"), -1));

        AddPhotoToSlot.Result result = addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        assertThat(result.extracted()).extracting(Item::sourcePhoto)
            .containsOnly(result.photoFilenames().get(0));
    }

    @Test
    void appendsToWhatTheSlotAlreadyHeld() {
        index.save(RACK, new Slot(A1, List.of(item("old")), Instant.EPOCH, List.of("earlier.jpg"), Instant.EPOCH));
        extractor.returns(new Extraction(item("new one"), 0), new Extraction(item("new two"), 1));

        addPhoto.execute(RACK, A1, List.of(photo("a"), photo("b")));

        Slot saved = index.get(RACK, A1).orElseThrow();
        assertThat(saved.items()).extracting(Item::description).containsExactly("old", "new one", "new two");
        assertThat(saved.photos()).hasSize(3).startsWith("earlier.jpg");
        assertThat(saved.lastVerified()).isAfter(Instant.EPOCH);
        assertThat(saved.printedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void refusesAnEmptyBatch() {
        assertThatThrownBy(() -> addPhoto.execute(RACK, A1, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one photo");

        assertThat(images.stored).isEmpty();
        assertThat(extractor.calls).isEmpty();
    }

    private static AddPhotoToSlot.Photo photo(String marker) {
        return new AddPhotoToSlot.Photo(marker.getBytes(StandardCharsets.UTF_8), "image/jpeg");
    }

    private static Item item(String description) {
        return new Item(description, description, null, null, 1, 0.9, List.of(), null, List.of(), null, null);
    }

    private static final class FakeImages implements ImageStore {
        private final List<String> stored = new ArrayList<>();

        @Override
        public String store(ContainerId container, SlotId slot, byte[] image, String contentType) {
            String marker = new String(image, StandardCharsets.UTF_8);
            stored.add(marker);
            return marker + "-" + stored.size() + ".jpg";
        }

        @Override
        public byte[] read(ContainerId container, SlotId slot, String filename) {
            return new byte[0];
        }

        @Override
        public List<String> list(ContainerId container, SlotId slot) {
            return List.copyOf(stored);
        }

        @Override
        public void delete(ContainerId container, SlotId slot, String filename) {
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
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
