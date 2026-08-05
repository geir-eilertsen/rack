package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import family.eilertsen.rack.domain.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFileUsageLogTest {

    @TempDir
    Path dataDir;

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
    }

    @Test
    void addsUpPerModel() throws IOException {
        JsonFileUsageLog log = load();
        log.record("claude-sonnet-4-6", 2038, 600);
        log.record("claude-sonnet-4-6", 2038, 700);
        log.record("claude-haiku-4-5", 900, 40);

        assertThat(log.byModel()).containsOnlyKeys("claude-sonnet-4-6", "claude-haiku-4-5");
        assertThat(log.byModel().get("claude-sonnet-4-6")).isEqualTo(new Usage(2, 4076, 1300));
        assertThat(log.byModel().get("claude-haiku-4-5")).isEqualTo(new Usage(1, 900, 40));
    }

    @Test
    void keepsTheTallyAcrossARestart() throws IOException {
        // What the rack has cost since it was built, not since the container
        // last came up — so it has to survive a deploy.
        load().record("claude-sonnet-4-6", 2038, 600);

        assertThat(load().byModel().get("claude-sonnet-4-6")).isEqualTo(new Usage(1, 2038, 600));
    }

    @Test
    void ignoresAModelItWasNotToldTheNameOf() throws IOException {
        JsonFileUsageLog log = load();
        log.record(null, 10, 10);
        log.record("  ", 10, 10);

        assertThat(log.byModel()).isEmpty();
    }

    @Test
    void bootsPastATallyItCannotRead() throws IOException {
        Files.writeString(dataDir.resolve("usage.json"), "{ this is not json", StandardCharsets.UTF_8);

        JsonFileUsageLog log = load();

        assertThat(log.byModel()).isEmpty();
        log.record("claude-haiku-4-5", 5, 5);
        assertThat(log.byModel()).containsKey("claude-haiku-4-5");
    }

    private JsonFileUsageLog load() throws IOException {
        JsonFileUsageLog log = new JsonFileUsageLog(dataDir.toString(), mapper);
        log.load();
        return log;
    }
}
