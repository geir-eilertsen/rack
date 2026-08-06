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

class ResyncSlotTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId A1 = new SlotId("A1");

    private FakeImages images;
    private FakeExtractor extractor;
    private FakeIndex index;
    private ResyncSlot resync;

    @BeforeEach
    void setUp() {
        images = new FakeImages();
        extractor = new FakeExtractor();
        index = new FakeIndex();
        resync = new ResyncSlot(images, extractor, index);
    }

    @Test
    void aPartNumberMatchesHoweverDifferentlyTheModelWordedItThisTime() {
        // The wording is the model's and changes between runs; the part number is
        // the part's. If a re-reading of the same drawer described as "NPN
        // small-signal transistors" did not find the "Transistor assortment"
        // already recorded, a resync would delete it and file it again.
        holds(item("Transistor assortment", "loose in a bag", "BC547", 20));
        extractor.returns(new Extraction(item("NPN small-signal transistors", "on a strip", "bc547", 18), 0));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front"));

        assertThat(preview.matched()).hasSize(1);
        assertThat(preview.gone()).isEmpty();
        assertThat(preview.added()).isEmpty();
        assertThat(preview.matched().get(0).index()).isZero();
        assertThat(preview.matched().get(0).qtyBefore()).isEqualTo(20);
        assertThat(preview.matched().get(0).qtyAfter()).isEqualTo(18);
    }

    @Test
    void aNameThatMostlyOverlapsIsTheSameThingEvenWithNoPartNumber() {
        // Most of what a drawer holds has no legible part number at all, so the
        // wording has to carry the match — otherwise every unlabelled bag of
        // bolts is reported gone and added back on every resync.
        holds(item("bag of M4 bolts", "DIN 933, zinc", null, 40));
        extractor.returns(new Extraction(item("M4 bolts", "in a zip bag", null, 35), 0));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front"));

        assertThat(preview.matched()).extracting(ResyncSlot.Matched::index).containsExactly(0);
        assertThat(preview.matched().get(0).frames()).containsExactly(0);
    }

    @Test
    void aQuantityNobodyEstimatedCountsAsOne() {
        holds(item("solder wick", "Chemtronics", null, null));
        extractor.returns(new Extraction(item("solder wick", "braid on a spool", null, null), 0));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front"));

        assertThat(preview.matched().get(0).qtyBefore()).isEqualTo(1);
        assertThat(preview.matched().get(0).qtyAfter()).isEqualTo(1);
    }

    @Test
    void anItemThePhotosNoLongerShowIsReportedGone() {
        holds(item("BC547 transistors", "bag", "BC547", 20),
              item("crimp terminals", "red, 6mm ring", null, 30));
        extractor.returns(new Extraction(item("BC547 transistors", "bag", "BC547", 20), 0));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front"));

        assertThat(preview.gone()).hasSize(1);
        assertThat(preview.gone().get(0).index()).isEqualTo(1);
        assertThat(preview.gone().get(0).current().name()).isEqualTo("crimp terminals");
    }

    @Test
    void somethingTheDrawerDidNotHoldBeforeIsReportedAdded() {
        holds(item("BC547 transistors", "bag", "BC547", 20));
        extractor.returns(
            new Extraction(item("BC547 transistors", "bag", "BC547", 20), 0),
            new Extraction(item("JST connectors", "2-pin, white", null, 8), List.of(1, 0)));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front", "side"));

        assertThat(preview.matched()).hasSize(1);
        assertThat(preview.added()).hasSize(1);
        assertThat(preview.added().get(0).found().name()).isEqualTo("JST connectors");
        assertThat(preview.added().get(0).frames()).containsExactly(1, 0);
    }

    @Test
    void twoReadingsOfOneThingCannotBothClaimTheSameRecordedItem() {
        // One-to-one is what makes the diff mean anything: let one extraction be
        // spent twice and a drawer holding one bag of bolts reports the second
        // bag gone while adding nothing, which is a deletion out of nowhere.
        holds(item("M4 bolts", "zinc", null, 40),
              item("M4 bolts", "stainless", null, 12));
        extractor.returns(new Extraction(item("M4 bolts", "in a bag", null, 40), 0));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front"));

        assertThat(preview.matched()).hasSize(1);
        assertThat(preview.gone()).hasSize(1);
        assertThat(preview.added()).isEmpty();
    }

    @Test
    void previewingWritesNothingAtAll() {
        // A preview the user abandons must leave no trace — neither an edited
        // slot nor frames on disk for the real run to have to clean up.
        holds(item("BC547 transistors", "bag", "BC547", 20));
        extractor.returns(new Extraction(item("nothing like it", "at all", null, 1), 0));

        resync.preview(RACK, A1, batch("front", "side"));

        assertThat(images.stored).isEmpty();
        assertThat(index.get(RACK, A1).orElseThrow().items()).hasSize(1);
    }

    @Test
    void keepingAnItemPreservesEveryHandEditedFieldButTakesTheNewCount() {
        // The reason a resync diffs instead of replacing: a corrected part
        // number, a name someone typed, and the answers under Ask AI are work
        // the camera cannot redo. Only the count came out of the new photos.
        Item edited = new Item("Zener 5V1", "hand-corrected note", "BZX55C5V1", "diodes", 12, 1.0,
            List.of("project-x"), null, List.of(new Item.QA("what package?", "DO-35", Instant.EPOCH)),
            "old-1.jpg", List.of("old-1.jpg"));
        holds(edited);

        Slot saved = resync.apply(RACK, A1, batch("front", "label"),
            new ResyncSlot.Decisions(List.of(new ResyncSlot.Keep(0, 40, List.of(1))), List.of()));

        Item kept = saved.items().get(0);
        assertThat(kept.name()).isEqualTo("Zener 5V1");
        assertThat(kept.description()).isEqualTo("hand-corrected note");
        assertThat(kept.partNumber()).isEqualTo("BZX55C5V1");
        assertThat(kept.category()).isEqualTo("diodes");
        assertThat(kept.confidence()).isEqualTo(1.0);
        assertThat(kept.tags()).containsExactly("project-x");
        assertThat(kept.qa()).hasSize(1);
        assertThat(kept.qtyEstimate()).isEqualTo(40);
        // The frames it was seen in are the only other thing that moves: the old
        // ones are about to be deleted, so pointing at them would be a dead link.
        assertThat(kept.seenIn()).containsExactly("label.jpg");
        assertThat(kept.sourcePhoto()).isEqualTo("label.jpg");
    }

    @Test
    void aKeptItemWithNoQuantityDecidedKeepsTheOneItHad() {
        holds(item("solder wick", "Chemtronics", null, 3));

        Slot saved = resync.apply(RACK, A1, batch("front"),
            new ResyncSlot.Decisions(List.of(new ResyncSlot.Keep(0, null, List.of(0))), List.of()));

        assertThat(saved.items().get(0).qtyEstimate()).isEqualTo(3);
    }

    @Test
    void whatIsNotKeptIsRemovedRatherThanZeroed() {
        // The drawer is empty of it, and an item recorded at zero is still an
        // item in the list — a lie the next search would repeat back.
        holds(item("BC547 transistors", "bag", "BC547", 20),
              item("crimp terminals", "red, 6mm ring", null, 30));

        Slot saved = resync.apply(RACK, A1, batch("front"),
            new ResyncSlot.Decisions(List.of(new ResyncSlot.Keep(1, 25, List.of(0))), List.of()));

        assertThat(saved.items()).hasSize(1);
        assertThat(saved.items().get(0).name()).isEqualTo("crimp terminals");
        assertThat(saved.items().get(0).qtyEstimate()).isEqualTo(25);
    }

    @Test
    void addedItemsFollowTheKeptOnesAndPointAtTheirOwnFrames() {
        holds(item("BC547 transistors", "bag", "BC547", 20));

        Slot saved = resync.apply(RACK, A1, batch("front", "side"),
            new ResyncSlot.Decisions(
                List.of(new ResyncSlot.Keep(0, 20, List.of(0))),
                List.of(new ResyncSlot.Add(item("JST connectors", "2-pin", null, 8), List.of(1)))));

        assertThat(saved.items()).extracting(Item::name)
            .containsExactly("BC547 transistors", "JST connectors");
        assertThat(saved.items().get(1).seenIn()).containsExactly("side.jpg");
    }

    @Test
    void aKeptItemNamingAFrameTheBatchHasNotGotPointsAtNone() {
        // Frame 7 of a one-photo batch is a stale or malformed decision, not
        // evidence. Falling back to the first photo would assert the item is in
        // a frame nobody claimed it was in — the same lie as pinning an
        // overruled "gone" item, reached by a different route. An added item is
        // the one case that may fall back, because it came out of the batch.
        holds(item("solder wick", "Chemtronics", null, 3));

        Slot saved = resync.apply(RACK, A1, batch("front"),
            new ResyncSlot.Decisions(List.of(new ResyncSlot.Keep(0, null, List.of(7))), List.of()));

        assertThat(saved.items().get(0).sourcePhoto()).isNull();
    }

    @Test
    void theSlotEndsUpHoldingOnlyTheNewFramesAndTheOldFilesAreDeleted() {
        // After a resync the photos are the evidence for what is in the drawer
        // now. An old frame left behind is evidence for a state that has gone,
        // and the photo is meant to be ground truth.
        index.save(RACK, new Slot(A1,
            List.of(shotIn(item("solder wick", "Chemtronics", null, 3), "old-1.jpg", "old-2.jpg")),
            Instant.EPOCH, Instant.EPOCH));

        Slot saved = resync.apply(RACK, A1, batch("front", "side"),
            new ResyncSlot.Decisions(
                List.of(new ResyncSlot.Keep(0, null, List.of(0, 1))), List.of()));

        assertThat(saved.frames()).containsExactly("front.jpg", "side.jpg");
        assertThat(images.deleted).containsExactly("old-1.jpg", "old-2.jpg");
        assertThat(saved.lastVerified()).isAfter(Instant.EPOCH);
        // A label already printed is still stuck to the drawer.
        assertThat(saved.printedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void aStaleKeepIndexIsRefusedBeforeAnythingIsStored() {
        // The preview and the confirmation are two requests, so the slot can
        // change in between. Failing after the photos were written would leave
        // frames on disk that no slot state names.
        holds(item("solder wick", "Chemtronics", null, 3));

        assertThatThrownBy(() -> resync.apply(RACK, A1, batch("front"),
            new ResyncSlot.Decisions(List.of(new ResyncSlot.Keep(4, null, List.of(0))), List.of())))
            .isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessageContaining("4")
            .hasMessageContaining("out of range");

        assertThat(images.stored).isEmpty();
    }

    @Test
    void refusesAnEmptyBatch() {
        // Without photos there is nothing to say the drawer is empty rather than
        // unphotographed, and this one deletes what it is not shown.
        assertThatThrownBy(() -> resync.preview(RACK, A1, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one photo");
        assertThatThrownBy(() -> resync.apply(RACK, A1, List.of(), new ResyncSlot.Decisions(List.of(), List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one photo");

        assertThat(images.stored).isEmpty();
        assertThat(extractor.calls).isEmpty();
    }

    /** Every item here was read from the drawer's one earlier frame. */
    private void holds(Item... items) {
        List<Item> shown = new ArrayList<>();
        for (Item i : items) shown.add(shotIn(i, "old-1.jpg"));
        index.save(RACK, new Slot(A1, List.copyOf(shown), Instant.EPOCH, Instant.EPOCH));
    }

    private static Item shotIn(Item i, String... frames) {
        return new Item(i.name(), i.description(), i.partNumber(), i.category(), i.qtyEstimate(),
            i.confidence(), i.tags(), i.embedding(), i.qa(), frames[0], List.of(frames));
    }

    private static List<byte[]> batch(String... markers) {
        List<byte[]> photos = new ArrayList<>();
        for (String marker : markers) photos.add(marker.getBytes(StandardCharsets.UTF_8));
        return photos;
    }

    private static Item item(String name, String description, String partNumber, Integer qty) {
        return new Item(name, description, partNumber, null, qty, 0.9, List.of(), null, List.of(), null, null);
    }

    private static final class FakeImages implements ImageStore {
        private final List<String> stored = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public String store(byte[] image, String contentType) {
            String marker = new String(image, StandardCharsets.UTF_8);
            stored.add(marker + ".jpg");
            return marker + ".jpg";
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
            deleted.add(filename);
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
    @Test
    void aDifferentPartNumberIsADifferentPartHoweverAlikeTheNames() {
        // This drawer holds 100K, 82K, 68K and 15K resistors, and BC547 beside
        // BC557. Their names overlap more than enough to pair on wording alone,
        // so where both sides carry a part number it has to be the whole answer
        // — otherwise a resync quietly rewrites one part into another.
        holds(item("BC547 transistor", "loose pile", "BC547", 30));
        extractor.returns(new Extraction(item("BC557 transistor", "loose pile", "BC557", 25), 0));

        ResyncSlot.Preview preview = resync.preview(RACK, A1, batch("front"));

        assertThat(preview.matched()).isEmpty();
        assertThat(preview.gone()).extracting(g -> g.current().partNumber()).containsExactly("BC547");
        assertThat(preview.added()).extracting(a -> a.found().partNumber()).containsExactly("BC557");
    }

    @Test
    void anItemKeptDespiteNotBeingInThePhotosPointsAtNoFrame() {
        // Overruling a "gone" verdict says the thing is in the drawer, not that
        // it is in these photos. Its old frames are about to be deleted, so
        // pinning it to a new one that does not show it would put a lie where
        // the evidence used to be. It keeps none and shows no strip.
        holds(item("Ferrite beads", "in a tiny bag at the back", null, 40));
        extractor.returns();

        Slot after = resync.apply(RACK, A1, batch("front"),
            new ResyncSlot.Decisions(List.of(new ResyncSlot.Keep(0, 40, List.of())), List.of()));

        Item kept = after.items().get(0);
        assertThat(kept.name()).isEqualTo("Ferrite beads");
        assertThat(kept.sourcePhoto()).isNull();
        assertThat(kept.seenIn()).isNull();
        // And with nothing in the drawer naming the frame that was just shot,
        // the frame does not survive either. A photograph of a drawer whose one
        // item is admittedly not in it is a photograph of nothing anyone has.
        assertThat(after.frames()).isEmpty();
        assertThat(images.deleted).contains("front.jpg");
    }

    @Test
    void anAddedItemAlwaysLandsOnAFrameBecauseItCameOutOfTheBatch() {
        holds();
        extractor.returns();

        Slot after = resync.apply(RACK, A1, batch("front", "label"), new ResyncSlot.Decisions(
            List.of(), List.of(new ResyncSlot.Add(item("Ferrite beads", "tiny bag", null, 40), List.of()))));

        assertThat(after.items().get(0).sourcePhoto()).isEqualTo("front.jpg");
    }

}
