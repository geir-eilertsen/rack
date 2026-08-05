package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Usage;
import family.eilertsen.rack.domain.port.UsageLog;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * One small file next to the slots, written the same way they are: to a
 * {@code .tmp} and then moved atomically, single writer on a single box.
 *
 * <p>A tally is worth keeping across restarts — the number that matters is what
 * the rack has cost since it was built, not since the container last came up.
 */
@Component
public class JsonFileUsageLog implements UsageLog {

    private static final Logger log = LoggerFactory.getLogger(JsonFileUsageLog.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, Usage> byModel = new TreeMap<>();

    public JsonFileUsageLog(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        Path dir = Path.of(dataDir).toAbsolutePath();
        Files.createDirectories(dir);
        this.file = dir.resolve("usage.json");
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            byModel.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, Usage>>() {}));
        } catch (IOException e) {
            // A tally is not worth refusing to boot over.
            log.warn("Ignoring unreadable {}: {}", file, e.toString());
        }
    }

    @Override
    public synchronized void record(String model, long inputTokens, long outputTokens) {
        if (model == null || model.isBlank()) return;
        byModel.merge(model, Usage.NONE.plus(inputTokens, outputTokens), (a, b) -> a.plus(b));
        try {
            Path tmp = file.resolveSibling("usage.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), byModel);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UncheckedIOException e) {
            // Counted in memory even if it could not be persisted — never let
            // bookkeeping break the call it is counting.
            log.warn("Could not persist usage: {}", e.toString());
        }
    }

    @Override
    public synchronized Map<String, Usage> byModel() {
        return new LinkedHashMap<>(byModel);
    }
}
