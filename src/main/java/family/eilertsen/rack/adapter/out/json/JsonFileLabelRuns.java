package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.LabelRun;
import family.eilertsen.rack.domain.port.LabelRuns;
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
import java.util.ArrayList;
import java.util.List;

/** One small file beside the slots, written the way they are. */
@Component
public class JsonFileLabelRuns implements LabelRuns {

    private static final Logger log = LoggerFactory.getLogger(JsonFileLabelRuns.class);

    /** Enough to see what happened without the file growing without end. */
    private static final int KEEP = 200;

    private final Path file;
    private final ObjectMapper mapper;
    private final List<LabelRun> runs = new ArrayList<>();

    public JsonFileLabelRuns(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        Path dir = Path.of(dataDir).toAbsolutePath();
        Files.createDirectories(dir);
        this.file = dir.resolve("label-runs.json");
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            runs.addAll(mapper.readValue(file.toFile(), new TypeReference<List<LabelRun>>() {}));
        } catch (IOException e) {
            // A lost ledger costs a misaligned sheet, not a broken app.
            log.warn("Ignoring unreadable {}: {}", file, e.toString());
        }
    }

    @Override
    public synchronized void record(LabelRun run) {
        runs.add(run);
        while (runs.size() > KEEP) runs.remove(0);
        try {
            Path tmp = file.resolveSibling("label-runs.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), runs);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not persist the label run: {}", e.toString());
        }
    }

    @Override
    public synchronized List<LabelRun> all() {
        return List.copyOf(runs);
    }
}
