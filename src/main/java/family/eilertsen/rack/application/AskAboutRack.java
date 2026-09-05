package family.eilertsen.rack.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.UsageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Answers a question about the whole rack — "I am restoring a Quad 606, do I
 * have all the parts?" — rather than about one item.
 *
 * <p><strong>The whole index goes into the prompt.</strong> All 152 items with
 * every field come to about 7,400 tokens, which is two cents at Sonnet rates and
 * a rounding error against a 1M window. Retrieval exists to solve a corpus that
 * does not fit; this one fits a hundred times over, so there is nothing to
 * retrieve and no top-k to tune.
 *
 * <p>That is not merely cheaper, it is the only version of this that can be
 * trusted. The question is <em>what am I missing</em>, and a retrieval step that
 * drops one item answers it by telling you to buy something you already own —
 * a false negative you cannot detect from the answer. Handing over everything
 * cannot fail that way. If the rack ever outgrows the window, the honest fix is
 * to say so rather than to quietly start sampling.
 *
 * <p>The division of labour: the model brings what a job needs, which is world
 * knowledge it has and the rack does not. The rack brings what is in the
 * drawers, which is the one thing the model cannot guess. So every claim of
 * possession has to name the drawer it came from, and {@link #verify} drops any
 * that names an item the index does not actually hold — the model is not
 * permitted to furnish this rack from memory.
 */
@Service
public class AskAboutRack {

    private static final Logger log = LoggerFactory.getLogger(AskAboutRack.class);

    private static final String SYSTEM = """
        You help someone work out whether their parts store has what a job needs.

        You are given the complete contents of their storage — every item, with
        the container and slot it is in. It is complete: if something is not
        listed, they do not have it.

        Two different jobs, and you must not confuse them:

        1. Work out what the job actually needs. This is yours to know. Be
           specific and practical — real component values, sizes, part numbers
           and quantities where a job implies them, not vague categories.
        2. Work out what they have. This comes ONLY from the list you are given.

        Rules that matter:
        - Never claim they have something unless it is in the list. Every
          "found" entry must quote an item's exact name and its real container
          and slot from the list.
        - A near-enough substitute is worth saying so: status "partial", with
          the substitute in "found" and the caveat in "note".
        - Quantities in the list are estimates from a photograph. If a job needs
          eight and the list says "about ten", say the count is worth checking.
        - Sort the checklist so the things they are missing come first.
        - If the question is not about a job needing parts, answer it from the
          list anyway and keep the checklist to what is relevant.

        Reply with JSON only, no markdown fence:
        {"summary": "two or three sentences, plain and direct",
         "checklist": [
           {"part": "what is needed",
            "why": "what it is for in this job",
            "status": "have" | "partial" | "missing",
            "found": [{"container": "the part before the slash", "slot": "the part after it", "item": "exact name from the list", "note": "optional"}],
            "note": "caveat, substitution, or what to buy"}]}
        """;

    private final PartIndex index;
    private final ContainerRegistry registry;
    private final ChatClient chat;
    private final UsageLog usage;
    private final ObjectMapper mapper;
    private final ChatOptions options;

    public AskAboutRack(
        PartIndex index,
        ContainerRegistry registry,
        ChatClient.Builder builder,
        UsageLog usage,
        ObjectMapper mapper,
        @Value("${rack.ai.ask-model}") String model,
        @Value("${rack.ai.rack-question-max-tokens}") int maxTokens
    ) {
        this.index = index;
        this.registry = registry;
        this.chat = builder.build();
        this.usage = usage;
        this.mapper = mapper;
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    public Answer execute(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        Inventory inventory = inventory(registry, index);
        if (inventory.lines().isEmpty()) {
            return new Answer(question.strip(),
                "There is nothing in the index yet, so there is nothing to check a job against.",
                List.of(), 0);
        }

        String prompt = "Everything in storage, one item per line, as "
            + "container/slot (a container id and a slot id, split on the slash) | name | "
            + "description | part number | category | quantity | tags:\n\n"
            + String.join("\n", inventory.lines())
            + "\n\nQuestion: " + question.strip();

        ChatResponse response = chat.prompt().options(options).system(SYSTEM).user(prompt).call().chatResponse();
        recordUsage(response);
        String raw = response.getResult().getOutput().getText();

        Reply reply;
        try {
            reply = mapper.readValue(ModelReply.json(raw), Reply.class);
        } catch (Exception e) {
            // A malformed reply is one unusable answer, not a broken rack — but it
            // is unreadable without seeing what came back, so log the head of it.
            log.warn("Could not read the answer about the rack: {} — reply began: {}",
                e.toString(), head(raw));
            throw new IllegalStateException("The model's answer could not be read. Try asking again.", e);
        }

        return new Answer(question.strip(), reply.summary(),
            verify(reply.checklist(), inventory.held()), inventory.lines().size());
    }

    /**
     * Drops anything the model claims to have found that is not in the index.
     *
     * <p>The one failure this feature cannot be allowed is telling someone they
     * own a part they do not — they would go to the drawer, and either the drawer
     * or the app would be wrong. Matching is on name within the named slot,
     * case- and space-insensitive, because the model quotes back a label it was
     * given rather than a key.
     */
    static List<Need> verify(List<Need> checklist, Map<String, List<String>> held) {
        if (checklist == null) return List.of();
        List<Need> checked = new ArrayList<>();
        for (Need need : checklist) {
            if (need == null || need.part() == null) continue;
            List<Found> real = keepReal(need.found(), held);
            // A "have" whose every citation was invented is a miss, not a have.
            String status = real.isEmpty() && !"missing".equals(need.status()) ? "missing" : need.status();
            checked.add(new Need(need.part(), need.why(), status, List.copyOf(real), need.note()));
        }
        return List.copyOf(checked);
    }

    /**
     * The citations the index agrees with, and only those.
     *
     * <p>Shared with {@link PlanPurchases}, because a work plan that says "use
     * your Dow Corning 340 from lab/2" is the same kind of claim as a checklist
     * saying you have it, and has to survive the same question.
     */
    static List<Found> keepReal(List<Found> found, Map<String, List<String>> held) {
        List<Found> real = new ArrayList<>();
        for (Found f : found == null ? List.<Found>of() : found) {
            if (f == null || f.item() == null) continue;
            Found placed = locate(f, held);
            if (placed != null) real.add(placed);
            else log.warn("Dropping a claim the index does not support: {}/{} \"{}\"",
                f.container(), f.slot(), f.item());
        }
        return List.copyOf(real);
    }

    /**
     * The drawer this citation actually means, or null if no drawer holds the item.
     *
     * <p>Every line of the listing begins "lab/10 | …", so a model asked for a
     * container and a slot separately will sometimes hand back the token it read:
     * {@code {"container": "lab/10", "slot": "lab/10"}}. The first purchase plan
     * lost 62 of about 100 tool references that way — every one of them a real
     * item in a real drawer, rejected over punctuation.
     *
     * <p>So the pair is resolved rather than demanded, and the standard is
     * unchanged: whichever reading is tried, that drawer has to hold that item.
     * A citation is repaired into the form the links need, or it is dropped.
     *
     * <p><strong>And a name the rack holds in exactly one drawer is placed in
     * that drawer</strong>, whatever drawer was cited. The first build talked
     * through on the live rack led with the Delta adapter — quoted by its exact
     * name, cited at lab/11, kept in box02/Box2 — and the prose praised a thing
     * the page showed no drawer for. The drawer link is read off the index
     * either way, so this is the index's answer and not the model's; a name two
     * drawers hold is still dropped, because then the drawer is the claim.
     */
    private static Found locate(Found f, Map<String, List<String>> held) {
        String item = normalise(f.item());
        for (String[] pair : readings(f)) {
            String[] at = drawer(pair[0], pair[1], held);
            if (at != null && held.get(at[0] + "/" + at[1]).contains(item)) {
                return new Found(at[0], at[1], f.item(), f.note());
            }
        }
        List<String[]> holding = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : held.entrySet()) {
            if (e.getValue().contains(item)) holding.add(e.getKey().split("/", 2));
        }
        if (holding.size() == 1) {
            String[] at = holding.get(0);
            log.info("Placing \"{}\" in {}/{} rather than the cited {}/{}",
                f.item(), at[0], at[1], f.container(), f.slot());
            return new Found(at[0], at[1], f.item(), f.note());
        }
        return null;
    }

    /**
     * The drawer these two name, case aside, spelled the way the index spells
     * it — a slot id is case-sensitive, and a link to "a1" would miss "A1".
     */
    private static String[] drawer(String container, String slot, Map<String, List<String>> held) {
        if (container == null || slot == null) return null;
        String want = key(container, slot);
        for (String k : held.keySet()) {
            if (normalise(k).equals(want)) return k.split("/", 2);
        }
        return null;
    }

    /** The pair as given, then either field read as a whole "container/slot". */
    private static List<String[]> readings(Found f) {
        List<String[]> readings = new ArrayList<>();
        readings.add(new String[] {f.container(), f.slot()});
        for (String combined : new String[] {f.container(), f.slot()}) {
            if (combined == null) continue;
            int cut = combined.indexOf('/');
            if (cut > 0 && cut < combined.length() - 1) {
                readings.add(new String[] {
                    combined.substring(0, cut).strip(), combined.substring(cut + 1).strip()});
            }
        }
        return readings;
    }

    /** One flat listing of the whole rack, plus what it holds for checking against. */
    static Inventory inventory(ContainerRegistry registry, PartIndex index) {
        List<String> lines = new ArrayList<>();
        Map<String, List<String>> held = new LinkedHashMap<>();
        for (Container container : registry.all()) {
            for (Slot slot : index.all(container.id())) {
                for (Item item : slot.items() == null ? List.<Item>of() : slot.items()) {
                    String where = container.id().value() + "/" + slot.id().value();
                    lines.add(where
                        + " | " + orBlank(item.name())
                        + " | " + orBlank(item.description())
                        + " | " + orBlank(item.partNumber())
                        + " | " + orBlank(item.category())
                        + " | " + (item.qtyEstimate() == null ? "?" : item.qtyEstimate())
                        + " | " + (item.tags() == null ? "" : String.join(",", item.tags()))
                        + verified(slot.lastVerified()));
                    held.computeIfAbsent(where, k -> new ArrayList<>())
                        .add(normalise(orBlank(item.name()).isBlank() ? item.description() : item.name()));
                }
            }
        }
        return new Inventory(List.copyOf(lines), held);
    }

    /**
     * How long ago a drawer was last looked at, so the model can caveat a part it
     * "has" on the strength of a year-old reading. Drift is the failure this whole
     * app is built against, and an answer that hides it is worse than no answer.
     */
    private static String verified(Instant lastVerified) {
        if (lastVerified == null) return " | last checked: never";
        long days = ChronoUnit.DAYS.between(lastVerified, Instant.now());
        if (days <= 0) return " | last checked: today";
        return " | last checked: " + days + (days == 1 ? " day ago" : " days ago");
    }

    private static String head(String raw) {
        String s = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    static String key(String container, String slot) {
        return normalise(container) + "/" + normalise(slot);
    }

    private static String normalise(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String orBlank(String s) {
        return s == null ? "" : s;
    }

    private void recordUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        org.springframework.ai.chat.metadata.Usage tokens = metadata == null ? null : metadata.getUsage();
        if (tokens == null) return;
        Integer in = tokens.getPromptTokens();
        Integer out = tokens.getCompletionTokens();
        usage.record(metadata.getModel(), in == null ? 0 : in, out == null ? 0 : out);
    }

    record Inventory(List<String> lines, Map<String, List<String>> held) {}

    /** What the model replies with, before any of it is believed. */
    record Reply(String summary, List<Need> checklist) {}

    public record Found(String container, String slot, String item, String note) {}

    public record Need(String part, String why, String status, List<Found> found, String note) {}

    /**
     * {@code itemsConsidered} is the whole index, every time — reported so the
     * answer carries the fact that nothing was sampled away before it was given.
     */
    public record Answer(String question, String summary,
                         List<Need> checklist,
                         @JsonProperty("items_considered") int itemsConsidered) {}
}
