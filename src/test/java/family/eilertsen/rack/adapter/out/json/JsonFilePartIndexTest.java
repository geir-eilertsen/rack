package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFilePartIndexTest {

    private static final ContainerId RACK = new ContainerId("rack");

    @TempDir
    Path dataDir;

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // Same shape as the app's mapper: snake_case, java.time, and the
        // @JsonComponent id adapters that write "A1" rather than {"value":"A1"}.
        // Without them the test would round-trip a format that never hits disk.
        SimpleModule ids = new SimpleModule();
        ids.addSerializer(SlotId.class, new SlotIdJson.Ser());
        ids.addDeserializer(SlotId.class, new SlotIdJson.De());
        ids.addSerializer(ContainerId.class, new ContainerIdJson.Ser());
        ids.addDeserializer(ContainerId.class, new ContainerIdJson.De());
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
        mapper.registerModule(ids);
        // And its tolerance for keys it does not know, which is what lets a field
        // be dropped without rewriting every file on disk.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Test
    void readsSlotJsonCarryingFieldsThatHaveSinceBeenDropped() throws IOException {
        // Two dead keys, both still on disk in every file that has not been
        // rewritten since: "photos" from when a slot kept its own list, and
        // "embedding" from a vector search that was never built. Reading past
        // them is what makes dropping a field as cheap as adding one — and
        // failing here would not be one missing value, it would be the whole
        // drawer refusing to load.
        writeSlot("C3", """
            {"id":"C3","items":[{"name":"Electrical tape","description":"one black roll","category":"other","qty_estimate":1,"confidence":0.9,"tags":["tape"],"embedding":null,"source_photo":"2026-08-04-1712.jpg","seen_in":["2026-08-04-1712.jpg"]}],"photos":["2026-08-04-1712.jpg","orphan.jpg"],"last_verified":null,"printed_at":null}
            """);

        Slot slot = load().get(RACK, new SlotId("C3")).orElseThrow();

        assertThat(slot.items()).hasSize(1);
        // And the drawer's frames are now what its items say they are — the key on
        // disk named a second file that no item did, and it does not come back.
        assertThat(slot.frames()).containsExactly("2026-08-04-1712.jpg");
    }

    @Test
    void readsSlotJsonWrittenBeforeItemsHadAName() throws IOException {
        // No "name" key at all — the shape every item on disk had before the split.
        writeSlot("A1", """
            {"id":"A1","items":[{"description":"Solder lugs - small metal ring terminals for soldering","part_number":null,"category":"connector","qty_estimate":20,"confidence":0.8,"tags":["lug"]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        JsonFilePartIndex index = load();

        Item item = index.get(RACK, new SlotId("A1")).orElseThrow().items().get(0);
        assertThat(item.name()).isNull();
        assertThat(item.description()).startsWith("Solder lugs");
    }

    @Test
    void searchReachesWhatWasAskedAboutAnItem() throws IOException {
        // The Q&A is the only part of an item written by the person who owns it.
        // Asking "is this a good selection for an audio amp" is how you know the
        // capacitor as the one you picked for the amp — and before this, that was
        // the one thing about it search could not see.
        writeSlot("B12", """
            {"id":"B12","items":[{"name":"220uF 100V capacitor","description":"radial electrolytic","category":"capacitor","qty_estimate":4,"confidence":0.9,"tags":[],"qa":[{"question":"is this a good selection for an audio amp","answer":"Generally yes, with caveats.","at":"2026-08-05T10:00:00Z"}]}],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("audio amp");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).item().name()).isEqualTo("220uF 100V capacitor");
    }

    @Test
    void aQuestionCountsForMoreThanTheAnswerToIt() throws IOException {
        // A question is short and deliberate: 29 characters at the median in this
        // rack. An answer is model prose at 946, so a word turning up somewhere
        // inside one is thinner evidence than the same word being asked about.
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"Ear tips","description":"silicone","category":"other","qty_estimate":6,"confidence":0.9,"tags":[],"qa":[{"question":"why do they itch after a while","answer":"Possible reasons for irritation.","at":"2026-08-05T10:00:00Z"}]}],"last_verified":null,"printed_at":null}
            """);
        writeSlot("A2", """
            {"id":"A2","items":[{"name":"Heat sink compound","description":"Dow Corning 340","category":"other","qty_estimate":1,"confidence":0.9,"tags":[],"qa":[{"question":"where to buy","answer":"Many suppliers stock it; some itch to sell you more.","at":"2026-08-05T10:00:00Z"}]}],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("itch");

        assertThat(hits).extracting(h -> h.item().name())
            .containsExactly("Ear tips", "Heat sink compound");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void aQaOnlyHitIsNeverConvincingEnoughToStopTheSearchWidening() throws IOException {
        // 2 for the question plus 0.5 for the answer is deliberately under the 3.0
        // that FindItems treats as a search that worked. An item found only through
        // its Q&A is a real hit and a weak one, so it must not suppress the
        // expansion — a word buried in a model's prose is not the rack telling you
        // it has the thing.
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"Intel NUC NUC5i3RYH","description":"mini PC","category":"other","qty_estimate":1,"confidence":0.9,"tags":[],"qa":[{"question":"how big ram","answer":"Max 16GB across two SO-DIMM slots.","at":"2026-08-05T10:00:00Z"}]}],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("16GB");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isLessThan(3.0);
    }

    @Test
    void aQaHitScoresOnceHoweverManyQuestionsWereAsked() throws IOException {
        // Otherwise curiosity about one item would quietly outrank an identical
        // item nobody happened to ask about.
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"Shelly relay","description":"one","category":"other","qty_estimate":1,"confidence":0.9,"tags":[],"qa":[{"question":"is it zigbee","answer":"No, wifi.","at":"2026-08-05T10:00:00Z"},{"question":"zigbee again","answer":"Still no zigbee.","at":"2026-08-05T11:00:00Z"},{"question":"zigbee really","answer":"Zigbee, no.","at":"2026-08-05T12:00:00Z"}]}],"last_verified":null,"printed_at":null}
            """);
        writeSlot("A2", """
            {"id":"A2","items":[{"name":"Zigbee dongle","description":"USB stick","category":"other","qty_estimate":1,"confidence":0.9,"tags":[],"last_verified":null}],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("zigbee");

        // The one actually called Zigbee wins, three questions or not.
        assertThat(hits).extracting(h -> h.item().name())
            .containsExactly("Zigbee dongle", "Shelly relay");
    }

    @Test
    void anItemWithNoQaSectionIsScoredAsBefore() throws IOException {
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"BC547 transistor","description":"TO-92","category":"transistor","qty_estimate":30,"confidence":0.9,"tags":[]}],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("BC547");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isEqualTo(3.0);
    }

    @Test
    void keywordSearchMatchesTheNameAndOutranksADescriptionOnlyHit() throws IOException {
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"BC547 transistor","description":"loose pile, TO-92 package","category":"transistor","qty_estimate":30,"confidence":0.9,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);
        writeSlot("A2", """
            {"id":"A2","items":[{"description":"assorted transistor offcuts","category":"other","qty_estimate":5,"confidence":0.5,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("transistor");

        assertThat(hits).hasSize(2);
        // Name (+3) plus category (+1) beats description (+2) alone.
        assertThat(hits.get(0).slot()).isEqualTo(new SlotId("A1"));
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void aMultiWordQueryMatchesWordsScatteredAcrossTheItem() throws IOException {
        // No field contains "black electrical tape" as a phrase, so a plain
        // substring search finds nothing at all.
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"Electrical tape","description":"one black roll, half used","category":"other","qty_estimate":1,"confidence":0.9,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("black electrical tape");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).slot()).isEqualTo(new SlotId("A1"));
    }

    @Test
    void anItemMatchingOnlySomeOfTheWordsIsNotAHit() throws IOException {
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"Electrical tape","description":"black roll","category":"other","qty_estimate":1,"confidence":0.9,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);
        writeSlot("A2", """
            {"id":"A2","items":[{"name":"Masking tape","description":"beige roll","category":"other","qty_estimate":1,"confidence":0.9,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("electrical tape");

        assertThat(hits).extracting(SearchHit::slot).containsExactly(new SlotId("A1"));
    }

    @Test
    void aWordThatMatchesNothingRulesOutTheItemsTheOtherWordsFound() throws IOException {
        // The resistors come on tape reels, so they match "tape" strongly — but
        // nothing here is "isolating", and they are not what was asked for.
        writeSlot("B7", """
            {"id":"B7","items":[{"name":"100K resistors","description":"strip cut from a tape reel","category":"resistor","qty_estimate":40,"confidence":0.9,"tags":["tape"]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        assertThat(load().searchByKeyword("isolating tape")).isEmpty();
    }

    @Test
    void aHyphenatedPartNumberStaysOneTerm() throws IOException {
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"BC547 transistor","description":"loose pile","part_number":"TO-220","category":"transistor","qty_estimate":30,"confidence":0.9,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        List<SearchHit> hits = load().searchByKeyword("TO-220");

        assertThat(hits).hasSize(1);
        // Scored as a single term, so no partial-phrase discount applies.
        assertThat(hits.get(0).score()).isEqualTo(3.0);
    }

    @Test
    void theVocabularyIsTheWordsTheRackActuallyUses() throws IOException {
        writeSlot("A1", """
            {"id":"A1","items":[{"name":"Electrical tape","description":"one black roll of PVC insulating tape","category":"other","qty_estimate":1,"confidence":0.9,"tags":["tape","pvc"]}],"photos":[],"last_verified":null,"printed_at":null}
            """);
        // Catalogued before the name/description split — its description stands in.
        writeSlot("A2", """
            {"id":"A2","items":[{"description":"Solder lugs - small metal ring terminals","category":"connector","qty_estimate":20,"confidence":0.8,"tags":[]}],"photos":[],"last_verified":null,"printed_at":null}
            """);

        assertThat(load().vocabulary())
            .contains("Electrical tape", "other", "tape", "pvc", "connector")
            .anyMatch(word -> word.startsWith("Solder lugs"))
            // Long descriptions would swamp the list; only the short labels belong.
            .doesNotContain("one black roll of PVC insulating tape");
    }

    @Test
    void aSlotWritesNoPhotoListOfItsOwn() throws IOException {
        // Slot.frames() answers which pictures show a drawer, but it is derived
        // from the items rather than a component of the record — deliberately, so
        // it is never written. Two copies of the same fact on disk is what let a
        // photograph be listed by a drawer and shown by nobody.
        JsonFilePartIndex index = load();
        Item item = new Item("M4 hex bolts", "bag of fifty", null, "fastener", 50, 0.9,
            List.of(), List.of(), "a.jpg", List.of("a.jpg", "b.jpg"));
        index.save(RACK, new Slot(new SlotId("B4"), List.of(item), null, null));

        String written = Files.readString(dataDir.resolve("rack").resolve("B4.json"), StandardCharsets.UTF_8);

        assertThat(written).doesNotContain("\"photos\"").doesNotContain("\"frames\"");
        // The frames are on disk exactly once, on the item that they show.
        assertThat(written).contains("\"seen_in\"").contains("b.jpg");
        assertThat(load().get(RACK, new SlotId("B4")).orElseThrow().frames())
            .containsExactly("a.jpg", "b.jpg");
    }

    @Test
    void theVocabularyComesOutInTheSameOrderEveryTime() throws IOException {
        // The expander sends the first N of these and this rack has outgrown N,
        // so which words are dropped is a real decision. It used to be made by
        // ConcurrentHashMap's hash order, which differs between restarts — and
        // "isolating tape" bridged to the electrical tape or found nothing at all
        // depending on the boot.
        for (String slot : new String[] {"E3", "A1", "C2", "B7", "D9"}) {
            writeSlot(slot, """
                {"id":"%s","items":[{"name":"item %s","category":"cat %s","tags":["tag %s"],"confidence":0.9}],"last_verified":null,"printed_at":null}
                """.formatted(slot, slot, slot, slot));
        }

        List<String> first = List.copyOf(load().vocabulary());
        List<String> again = List.copyOf(load().vocabulary());

        assertThat(first).isEqualTo(again);
        // Slot order, so the list is the rack read top to bottom rather than by hash.
        assertThat(first).startsWith("item A1", "cat A1", "tag A1", "item B7");
    }

    @Test
    void aWordSpelledTwoWaysTakesOnlyOnePlaceInTheVocabulary() throws IOException {
        // An item named "Electrical tape" and tagged "electrical tape" spent two
        // of a capped list's places on one word.
        JsonFilePartIndex index = new JsonFilePartIndex(dataDir.toString(), mapper);
        Item item = new Item("Electrical tape", "one black roll", null, "other", 1, 0.9,
            List.of("electrical tape", "ELECTRICAL TAPE", "pvc"), List.of(), null, null);
        index.save(RACK, new Slot(new SlotId("A9"), List.of(item), null, null));

        assertThat(index.vocabulary()).containsExactly("Electrical tape", "other", "pvc");
    }

    @Test
    void savedItemsKeepTheirNameAcrossAReload() throws IOException {
        JsonFilePartIndex index = load();
        Item item = new Item("M4 hex bolts", "bag of about fifty, DIN 933", null, "fastener",
            50, 0.9, List.of("M4"), List.of(), null, null);
        index.save(RACK, new Slot(new SlotId("B2"), List.of(item), null, null));

        Item reloaded = load().get(RACK, new SlotId("B2")).orElseThrow().items().get(0);

        assertThat(reloaded.name()).isEqualTo("M4 hex bolts");
        assertThat(reloaded.description()).isEqualTo("bag of about fifty, DIN 933");
    }

    private JsonFilePartIndex load() throws IOException {
        JsonFilePartIndex index = new JsonFilePartIndex(dataDir.toString(), mapper);
        index.load();
        return index;
    }

    private void writeSlot(String slot, String json) throws IOException {
        Path dir = dataDir.resolve(RACK.value());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(slot + ".json"), json, StandardCharsets.UTF_8);
    }
}
