package family.eilertsen.rack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * The whole context, wired the way the image wires it. Every other test builds
 * its objects by hand, so a bean Spring cannot construct — a service with two
 * constructors and nothing saying which — passed the suite and failed only on
 * the box, with the site down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationBootsTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("rack.data-dir", () -> dataDir.toString());
        registry.add("spring.ai.anthropic.api-key", () -> "test-key-not-used");
    }

    @Test
    void contextLoads() {
    }
}
