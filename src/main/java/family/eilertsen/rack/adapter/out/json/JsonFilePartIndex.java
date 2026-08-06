package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class JsonFilePartIndex implements PartIndex {

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final Map<ContainerId, Map<SlotId, Slot>> byContainer = new ConcurrentHashMap<>();

    public JsonFilePartIndex(@Value("${rack.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        this.mapper = mapper;
        Files.createDirectories(this.dataDir);
    }

    @PostConstruct
    public void load() throws IOException {
        if (!Files.isDirectory(dataDir)) return;
        try (Stream<Path> containers = Files.list(dataDir)) {
            containers.filter(Files::isDirectory).forEach(this::loadContainer);
        }
    }

    private void loadContainer(Path containerDir) {
        ContainerId cid;
        try {
            cid = new ContainerId(containerDir.getFileName().toString());
        } catch (IllegalArgumentException e) {
            return;
        }
        Map<SlotId, Slot> slots = new ConcurrentHashMap<>();
        try (Stream<Path> files = Files.list(containerDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        Slot slot = mapper.readValue(p.toFile(), Slot.class);
                        slots.put(slot.id(), slot);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to load " + p, e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed listing " + containerDir, e);
        }
        byContainer.put(cid, slots);
    }

    @Override
    public Optional<Slot> get(ContainerId container, SlotId slot) {
        Map<SlotId, Slot> slots = byContainer.get(container);
        return slots == null ? Optional.empty() : Optional.ofNullable(slots.get(slot));
    }

    @Override
    public synchronized void save(ContainerId container, Slot slot) {
        byContainer.computeIfAbsent(container, k -> new ConcurrentHashMap<>()).put(slot.id(), slot);
        Path containerDir = dataDir.resolve(container.value());
        try {
            Files.createDirectories(containerDir);
            Path target = containerDir.resolve(slot.id().value() + ".json");
            Path tmp = containerDir.resolve(slot.id().value() + ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), slot);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Collection<Slot> all(ContainerId container) {
        Map<SlotId, Slot> slots = byContainer.get(container);
        return slots == null ? List.of() : List.copyOf(slots.values());
    }

    @Override
    public synchronized void forget(ContainerId container) {
        byContainer.remove(container);
        Path dir = dataDir.resolve(container.value());
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed clearing " + dir, e);
        }
        // Only if nothing else is in there. Archived label sheets are the record
        // of what was physically printed, and that outlives the registration.
        try (Stream<Path> left = Files.list(dir)) {
            if (left.findAny().isEmpty()) Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed tidying " + dir, e);
        }
    }

    @Override
    public List<SearchHit> searchByKeyword(String query) {
        if (query == null || query.isBlank()) return List.of();
        String phrase = query.toLowerCase(Locale.ROOT).strip();
        List<String> terms = terms(phrase);
        List<SearchHit> hits = new ArrayList<>();
        for (Map.Entry<ContainerId, Map<SlotId, Slot>> e : byContainer.entrySet()) {
            ContainerId cid = e.getKey();
            for (Slot slot : e.getValue().values()) {
                List<Item> items = slot.items();
                for (int i = 0; i < items.size(); i++) {
                    Item item = items.get(i);
                    double score = matchScore(item, terms, phrase);
                    if (score > 0) {
                        hits.add(new SearchHit(cid, slot.id(), i, item, score, slot.lastVerified()));
                    }
                }
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    /**
     * A multi-word query is scored word by word — "black electrical tape" has to
     * find the item described as "electrical tape, black roll", which no single
     * substring match ever would. Split on whitespace only, so a part number like
     * "TO-220" stays one term.
     */
    private static List<String> terms(String phrase) {
        List<String> terms = new ArrayList<>();
        for (String term : phrase.split("\\s+")) {
            if (term.length() >= 2 && !terms.contains(term)) terms.add(term);
        }
        // Everything was a single character ("5 v"): fall back to the raw phrase
        // rather than matching nothing.
        return terms.isEmpty() ? List.of(phrase) : terms;
    }

    /**
     * Every word has to be accounted for. Searching "isolating tape" must not
     * return the resistors that happen to come on tape reels: "isolating" is the
     * word doing the work, and an item that matches only "tape" is not what was
     * asked for however high it scores on that one word.
     *
     * <p>Dropping those leaves the query with nothing, which is the honest
     * answer — and it is what lets {@code FindItems} see the query failed and
     * widen it into the words this rack does use.
     */
    private static double matchScore(Item item, List<String> terms, String phrase) {
        double score = 0;
        for (String term : terms) {
            double termScore = termScore(item, term);
            if (termScore == 0) return 0;
            score += termScore;
        }
        // The words in the order the user typed them beats them scattered.
        if (terms.size() > 1 && (contains(item.name(), phrase) || contains(item.description(), phrase))) {
            score += 3;
        }
        return score;
    }

    /**
     * What was asked about an item is worth as much as its own description; what
     * the model answered is worth much less than either.
     *
     * <p>A question is a human's words for the thing in front of them, typed
     * deliberately and short — 29 characters at the median in this rack. An answer
     * is model prose at 946, and a word appearing somewhere inside it is thin
     * evidence: the heat sink compound's answer says "internet" and "sources"
     * without being either, and the capacitor's mentions itching.
     *
     * <p><strong>Together they stay under the weight of a name.</strong> 2 + 0.5
     * is deliberately below {@code FindItems.CONVINCING}, so an item found only
     * through its Q&amp;A is a hit but never the kind of hit that stops the query
     * being widened. Searching "16GB" turns up the NUC because its answer lists
     * the maximum, and still looks for related words, which is the right reading
     * of evidence that thin.
     *
     * <p>Scored once per field however many exchanges an item has, the way tags
     * are — otherwise asking five questions would make an item five times as
     * findable as the identical one nobody asked about.
     */
    private static final double QUESTION_WEIGHT = 2;
    private static final double ANSWER_WEIGHT = 0.5;

    private static double termScore(Item item, String term) {
        double score = 0;
        if (contains(item.partNumber(), term)) score += 3;
        if (contains(item.name(), term)) score += 3;
        if (contains(item.description(), term)) score += 2;
        if (contains(item.category(), term)) score += 1;
        if (item.tags() != null) {
            for (String tag : item.tags()) if (contains(tag, term)) { score += 1; break; }
        }
        score += qaScore(item, term);
        return score;
    }

    /** What you asked about a thing is part of what you know it by. */
    private static double qaScore(Item item, String term) {
        if (item.qa() == null) return 0;
        boolean inQuestion = false;
        boolean inAnswer = false;
        for (Item.QA exchange : item.qa()) {
            if (exchange == null) continue;
            inQuestion |= contains(exchange.question(), term);
            inAnswer |= contains(exchange.answer(), term);
            if (inQuestion && inAnswer) break;
        }
        return (inQuestion ? QUESTION_WEIGHT : 0) + (inAnswer ? ANSWER_WEIGHT : 0);
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }


    /**
     * Every distinct short label in the index, in a stable order.
     *
     * <p><strong>Sorted, because the caller truncates.</strong> The expander sends
     * the first N of these and this rack has outgrown N, so which words are
     * dropped is a real decision — and it used to be made by {@link
     * java.util.concurrent.ConcurrentHashMap}'s hash order, which is arbitrary and
     * differs between restarts. The result was that "isolating tape" bridged to
     * the electrical tape or found nothing at all depending on the boot: the
     * feature the whole search design is named for, working as a coin flip. Same
     * rack, same words, same answer is the least this owes.
     *
     * <p>Deduplicated ignoring case, since an item named "Electrical tape" and
     * tagged "electrical tape" spends two of those places on one word.
     */
    @Override
    public Set<String> vocabulary() {
        Set<String> words = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        List<ContainerId> containers = new ArrayList<>(byContainer.keySet());
        containers.sort(Comparator.comparing(ContainerId::value));
        for (ContainerId cid : containers) {
            Map<SlotId, Slot> slots = byContainer.get(cid);
            if (slots == null) continue;
            List<SlotId> ids = new ArrayList<>(slots.keySet());
            ids.sort(Comparator.comparing(SlotId::value));
            for (SlotId sid : ids) {
                Slot slot = slots.get(sid);
                if (slot == null || slot.items() == null) continue;
                for (Item item : slot.items()) {
                    add(words, seen, label(item));
                    add(words, seen, item.category());
                    if (item.tags() != null) for (String tag : item.tags()) add(words, seen, tag);
                }
            }
        }
        return words;
    }

    /**
     * One question, one place to ask it: a photograph is in use when an item
     * names it. Items are the physical things, so a picture nothing points at is
     * a picture of nothing anybody has.
     */
    @Override
    public Set<String> photosInUse() {
        Set<String> used = new LinkedHashSet<>();
        for (Map<SlotId, Slot> slots : byContainer.values()) {
            for (Slot slot : slots.values()) {
                used.addAll(slot.frames());
            }
        }
        return used;
    }

    /** Items catalogued before the name/description split have only a description — clip it. */
    private static String label(Item item) {
        if (item.name() != null && !item.name().isBlank()) return item.name();
        String description = item.description();
        if (description == null) return null;
        String d = description.strip();
        return d.length() <= 60 ? d : d.substring(0, 60);
    }

    /** Keeps the first spelling of a word and counts later casings as the same. */
    private static void add(Set<String> words, Set<String> seen, String word) {
        if (word == null || word.isBlank()) return;
        String w = word.strip();
        if (seen.add(w.toLowerCase(Locale.ROOT))) words.add(w);
    }
}
