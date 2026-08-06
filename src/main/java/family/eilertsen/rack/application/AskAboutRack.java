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
            "found": [{"container": "id", "slot": "id", "item": "exact name from the list", "note": "optional"}],
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
            + "container/slot | name | description | part number | category | quantity | tags:\n\n"
            + String.join("\n", inventory.lines())
            + "\n\nQuestion: " + question.strip();

        ChatResponse response = chat.prompt().options(options).system(SYSTEM).user(prompt).call().chatResponse();
        recordUsage(response);
        String raw = response.getResult().getOutput().getText();

        Reply reply;
        try {
            reply = mapper.readValue(strip(raw), Reply.class);
        } catch (Exception e) {
            // A malformed reply is one unusable answer, not a broken rack.
            log.warn("Could not read the answer about the rack: {}", e.toString());
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
            List<Found> real = new ArrayList<>();
            for (Found found : need.found() == null ? List.<Found>of() : need.found()) {
                if (found == null || found.item() == null) continue;
                List<String> names = held.get(key(found.container(), found.slot()));
                if (names != null && names.contains(normalise(found.item()))) real.add(found);
                else log.warn("Dropping a claim the index does not support: {}/{} \"{}\"",
                    found.container(), found.slot(), found.item());
            }
            // A "have" whose every citation was invented is a miss, not a have.
            String status = real.isEmpty() && !"missing".equals(need.status()) ? "missing" : need.status();
            checked.add(new Need(need.part(), need.why(), status, List.copyOf(real), need.note()));
        }
        return List.copyOf(checked);
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
                    held.computeIfAbsent(key(container.id().value(), slot.id().value()), k -> new ArrayList<>())
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

    /** Models sometimes fence JSON however firmly they are asked not to. */
    static String strip(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.strip();
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
