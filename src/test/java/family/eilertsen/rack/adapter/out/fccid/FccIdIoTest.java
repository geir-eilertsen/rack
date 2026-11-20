package family.eilertsen.rack.adapter.out.fccid;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FccIdIoTest {

    @TempDir Path dir;

    @Test
    void thePagesTitleIsPlainText() {
        assertThat(FccIdIo.title("<html><head><title>FCC ID DZLMRCJ134 - 2.4GHz cordless mouse</title>", "DZLMRCJ134"))
            .isEqualTo("FCC ID DZLMRCJ134 - 2.4GHz cordless mouse");
        assertThat(FccIdIo.title("<title>\n  Router &amp; AP &lt;5GHz&gt;\n</title>", "X1Y2")).isEqualTo("Router & AP <5GHz>");
        assertThat(FccIdIo.title("<html>no title</html>", "X1Y2")).isEqualTo("FCC ID X1Y2");
    }

    @Test
    void keptAnswersAreAnsweredWithoutAsking() throws Exception {
        Files.writeString(dir.resolve("fcc-ids.json"), """
            {"DZLMRCJ134":{"url":"https://fccid.io/DZLMRCJ134","title":"FCC ID DZLMRCJ134 - mouse"},
             "ZZZQQQ999":{"url":null,"title":null}}
            """);
        // No client: anything that is not a kept answer would fail.
        FccIdIo io = new FccIdIo(dir.toString(), new ObjectMapper(), null);

        assertThat(io.asked("dzlmrcj134")).isTrue();
        assertThat(io.lookup("DZLMRCJ134")).hasValueSatisfying(l -> assertThat(l.title()).isEqualTo("FCC ID DZLMRCJ134 - mouse"));
        assertThat(io.lookup("ZZZQQQ999")).isEmpty();
        assertThat(io.asked("ABC123")).isFalse();
    }

    @Test
    void onlyAnFccIdGoesIntoTheAddress() {
        FccIdIo io = new FccIdIo(dir.toString(), new ObjectMapper(), null);
        assertThatThrownBy(() -> io.lookup("../admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> io.lookup("ABC 123")).isInstanceOf(IllegalArgumentException.class);
    }
}
