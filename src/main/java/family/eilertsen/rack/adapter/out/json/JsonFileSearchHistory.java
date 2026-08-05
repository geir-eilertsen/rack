package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.port.SearchHistory;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Kept on the box rather than in the browser, so the search you did at the rack
 * on your phone is still there on the laptop afterwards. One small file, written
 * the way slot state is.
 */
@Component
public class JsonFileSearchHistory implements SearchHistory {

    private static final Logger log = LoggerFactory.getLogger(JsonFileSearchHistory.class);

    /** Enough to cover the things you look for repeatedly, few enough to scan. */
    private static final int KEEP = 12;
    /** A single letter is someone starting to type, never something they looked for. */
    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 100;

    private final Path file;
    private final ObjectMapper mapper;
    private final Deque<String> recent = new ArrayDeque<>();

    public JsonFileSearchHistory(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        Path dir = Path.of(dataDir).toAbsolutePath();
        Files.createDirectories(dir);
        this.file = dir.resolve("searches.json");
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> stored = mapper.readValue(file.toFile(), new TypeReference<List<String>>() {});
            stored.stream().limit(KEEP).forEach(recent::addLast);
        } catch (IOException e) {
            log.warn("Ignoring unreadable {}: {}", file, e.toString());
        }
    }

    @Override
    public synchronized void remember(String query) {
        if (query == null) return;
        String trimmed = query.strip();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) return;

        // "b", "ba", "batt", "batteri" is one search being typed, not four.
        // Timing cannot tell them apart — pause long enough between keystrokes
        // and every prefix looks like a query someone meant — but the text can:
        // when one of a pair extends the other they belong to the same chain, so
        // the newer wins. That also covers backspacing to a shorter query.
        String head = recent.peekFirst();
        if (head != null && sameChain(head, trimmed)) recent.removeFirst();

        // Searching the same thing again moves it to the front rather than
        // filling the list with itself.
        recent.removeIf(existing -> existing.equalsIgnoreCase(trimmed));
        recent.addFirst(trimmed);
        while (recent.size() > KEEP) recent.removeLast();

        try {
            Path tmp = file.resolveSibling("searches.json.tmp");
            mapper.writeValue(tmp.toFile(), List.copyOf(recent));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not persist search history: {}", e.toString());
        }
    }

    @Override
    public synchronized List<String> recent() {
        return List.copyOf(recent);
    }

    /** One query extends the other, so they are the same search mid-typing. */
    private static boolean sameChain(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        return x.startsWith(y) || y.startsWith(x);
    }
}
