package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFileSearchHistoryTest {

    @TempDir
    Path dataDir;

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    @Test
    void mostRecentComesFirst() throws IOException {
        JsonFileSearchHistory history = load();
        history.remember("batteries");
        history.remember("heat shrink");

        assertThat(history.recent()).containsExactly("heat shrink", "batteries");
    }

    @Test
    void searchingTheSameThingAgainMovesItUpRatherThanRepeatingIt() throws IOException {
        JsonFileSearchHistory history = load();
        history.remember("batteries");
        history.remember("heat shrink");
        history.remember("BATTERIES");

        assertThat(history.recent()).containsExactly("BATTERIES", "heat shrink");
    }

    @Test
    void keepsTheListShortEnoughToScan() throws IOException {
        JsonFileSearchHistory history = load();
        for (int i = 1; i <= 20; i++) history.remember("query " + i);

        assertThat(history.recent()).hasSize(12).startsWith("query 20").endsWith("query 9");
    }

    @Test
    void survivesARestart() throws IOException {
        load().remember("batteries");

        assertThat(load().recent()).containsExactly("batteries");
    }

    @Test
    void aQueryBeingTypedIsOneSearchAndNotFour() throws IOException {
        // What actually reached the list before: typing "batteri" slowly enough
        // that each prefix settled left b, ba, batt and batteri all remembered.
        JsonFileSearchHistory history = load();
        history.remember("ba");
        history.remember("bat");
        history.remember("batt");
        history.remember("batteri");

        assertThat(history.recent()).containsExactly("batteri");
    }

    @Test
    void backspacingToAShorterQueryKeepsWhatYouStoppedOn() throws IOException {
        JsonFileSearchHistory history = load();
        history.remember("batteries");
        history.remember("batt");

        assertThat(history.recent()).containsExactly("batt");
    }

    @Test
    void onlyCollapsesAgainstTheSearchBeingTyped() throws IOException {
        // "led" is not mid-typing here — something else was searched in between,
        // so it is a search of its own and both are worth keeping.
        JsonFileSearchHistory history = load();
        history.remember("led");
        history.remember("resistor");
        history.remember("led strip");

        assertThat(history.recent()).containsExactly("led strip", "resistor", "led");
    }

    @Test
    void ignoresWhatIsNotWorthRemembering() throws IOException {
        JsonFileSearchHistory history = load();
        history.remember(null);
        history.remember("   ");
        history.remember("b");                  // someone starting to type
        history.remember("x".repeat(200));

        assertThat(history.recent()).isEmpty();
    }

    @Test
    void bootsPastAHistoryItCannotRead() throws IOException {
        Files.writeString(dataDir.resolve("searches.json"), "not json at all", StandardCharsets.UTF_8);

        JsonFileSearchHistory history = load();
        history.remember("batteries");

        assertThat(history.recent()).containsExactly("batteries");
    }

    private JsonFileSearchHistory load() throws IOException {
        JsonFileSearchHistory history = new JsonFileSearchHistory(dataDir.toString(), mapper);
        history.load();
        return history;
    }
}
