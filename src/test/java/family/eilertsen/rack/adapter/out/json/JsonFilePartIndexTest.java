package family.eilertsen.rack.adapter.out.json;

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
    void savedItemsKeepTheirNameAcrossAReload() throws IOException {
        JsonFilePartIndex index = load();
        Item item = new Item("M4 hex bolts", "bag of about fifty, DIN 933", null, "fastener",
            50, 0.9, List.of("M4"), null, List.of(), null);
        index.save(RACK, new Slot(new SlotId("B2"), List.of(item), null, List.of(), null));

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
