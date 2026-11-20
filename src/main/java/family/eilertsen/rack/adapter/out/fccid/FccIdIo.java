package family.eilertsen.rack.adapter.out.fccid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.FccListing;
import family.eilertsen.rack.domain.port.FccRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fccid.io, which mirrors the FCC's register with one page per grant at
 * {@code https://fccid.io/<FCC ID>}: 200 for a grant it has, 404 for one it
 * has not. The page's title names the device ("FCC ID DZLMRCJ134 - 2.4GHz
 * cordless mouse"), which becomes the link's title.
 *
 * <p><strong>Asked once per ID, and the answer kept</strong> in
 * {@code data/fcc-ids.json} — found or not. A grant does not come and go,
 * and keeping the answer is what lets a link the owner removed stay removed:
 * the sweep only asks about IDs it has never asked about. A request that
 * fails is not an answer and is not kept, so it is asked again later.
 *
 * <p>Sends the FCC ID and nothing else; the client names itself generically.
 */
@Component
public class FccIdIo implements FccRegistry {

    private static final Logger log = LoggerFactory.getLogger(FccIdIo.class);
    static final String BASE = "https://fccid.io/";
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final HttpClient http;
    private final Path file;
    private final ObjectMapper mapper;
    /** FCC ID → its listing, or a listing with a null url for "not listed". */
    private final Map<String, FccListing> answers = new LinkedHashMap<>();

    @Autowired
    public FccIdIo(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) {
        this(dataDir, mapper, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    FccIdIo(String dataDir, ObjectMapper mapper, HttpClient http) {
        this.http = http;
        this.mapper = mapper;
        this.file = Path.of(dataDir).toAbsolutePath().normalize().resolve("fcc-ids.json");
        if (Files.exists(file)) try {
            answers.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, FccListing>>() {}));
        } catch (IOException e) {
            log.warn("Ignoring unreadable {}: {}", file, e.toString());
        }
    }

    @Override
    public synchronized boolean asked(String fccId) {
        return answers.containsKey(key(fccId));
    }

    @Override
    public Optional<FccListing> lookup(String fccId) {
        String id = key(fccId);
        synchronized (this) {
            if (answers.containsKey(id)) return Optional.of(answers.get(id)).filter(l -> l.url() != null);
        }
        String url = BASE + id;
        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "rack-inventory")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("fccid.io could not be reached for " + id, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted asking fccid.io about " + id, e);
        }
        FccListing answer;
        if (res.statusCode() == 200) {
            answer = new FccListing(url, title(res.body(), id));
        } else if (res.statusCode() == 404) {
            answer = new FccListing(null, null);
        } else {
            throw new IllegalStateException("fccid.io answered " + res.statusCode() + " for " + id);
        }
        remember(id, answer);
        log.info("fccid.io {} {}", id, answer.url() != null ? "is listed" : "is not listed");
        return Optional.of(answer).filter(l -> l.url() != null);
    }

    /** The page's own title, as plain text, or a plain one when it has none. */
    static String title(String html, String id) {
        Matcher m = TITLE.matcher(html == null ? "" : html);
        String t = m.find() ? m.group(1) : "";
        t = t.replaceAll("\\s+", " ").strip()
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'");
        if (t.isEmpty()) t = "FCC ID " + id;
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private synchronized void remember(String id, FccListing answer) {
        answers.put(id, answer);
        try {
            Path tmp = file.resolveSibling("fcc-ids.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), answers);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Still remembered until the next restart; asking again then is harmless.
            log.warn("Could not keep fccid.io answers in {}: {}", file, e.toString());
        }
    }

    /** Goes into a URL, so only what an FCC ID can be: letters, digits and dashes. */
    private static String key(String fccId) {
        String id = fccId == null ? "" : fccId.strip().toUpperCase(Locale.ROOT);
        if (!id.matches("[A-Z0-9][A-Z0-9-]{3,19}")) throw new IllegalArgumentException("Not an FCC ID: " + fccId);
        return id;
    }
}
