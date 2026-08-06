package family.eilertsen.rack.application;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the gaps {@link AskAboutRack} found into a shopping run and a way to do
 * the job: one list per supplier, and numbered steps for the work itself.
 *
 * <p><strong>Which supplier is read off the rack, not guessed.</strong> The
 * descriptions carry where things came from — "Panasonic RoHS Farnell 876-7670"
 * on a capacitor, "Clas Ohlson 32-7965" on a mains splitter, Biltema on the
 * crimp terminals — so the inventory itself says which places this person
 * actually buys from and for what kind of part. That is the same move search
 * makes with {@code vocabulary()}: ground the answer in what this rack shows
 * rather than in what a model knows about shops in general. A hardcoded supplier
 * list would be out of date within the year and wrong for whoever runs this next.
 *
 * <p><strong>No prices, no stock, no invented order codes.</strong> A model
 * cannot know any of the three, and a shopping list that states them confidently
 * is worse than one that does not mention them: you would carry a wrong total to
 * the till rather than looking it up. It may quote an order code only where the
 * inventory already shows one, because that is a fact about a thing on the
 * shelf. Everything else is a search term for the supplier's own site.
 *
 * <p>The whole index goes in, as it does for the question that precedes this, so
 * the plan can say which step uses the compound already in lab/2 — and every
 * such citation goes through {@link AskAboutRack#keepReal} for the same reason:
 * a plan that sends you to a drawer for a tool that was never there is worse
 * than one that tells you to buy it.
 */
@Service
public class PlanPurchases {

    private static final Logger log = LoggerFactory.getLogger(PlanPurchases.class);

    private static final String SYSTEM = """
        You plan a parts order and the work that follows it, for someone who
        keeps a catalogued parts store and has just found out what they lack.

        You are given: their region, the complete contents of their storage, the
        project, and the list of things they need to buy.

        GROUP THE ORDER BY SUPPLIER. Every part goes to exactly one supplier.
        Choosing well is most of the value here:
        - Read the inventory for where they already shop. Descriptions carry
          order codes and shop names from previous purchases. A supplier they
          have demonstrably used beats one you merely know of, and tell them
          that is why you picked it.
        - Prefer suppliers in or shipping to their region. Say plainly when a
          part realistically only comes from further afield.
        - Keep the number of suppliers down. One order of fifteen lines beats
          five orders of three; shipping and waiting are real costs.
        - Match the supplier to the part. A hardware or consumer chain is right
          for cable, fasteners and consumables and wrong for semiconductors.

        NEVER STATE A PRICE, A TOTAL, OR WHETHER SOMETHING IS IN STOCK. You
        cannot know any of them and a wrong one is worse than none. Do not
        invent order codes, part numbers or URLs. You may quote an order code
        ONLY if it already appears in the inventory. Otherwise give a "search"
        string they can paste into that supplier's own site.

        THEN WRITE THE INSTRUCTIONS for doing the job, in order, assuming
        competence but not familiarity with this particular device. Where a step
        uses something they already own, cite it in "uses" with its real
        container and slot from the inventory. Put genuine hazards in
        "cautions" — mains voltage, charged capacitors, hot parts, anything that
        damages the device or the person if done in the wrong order.

        Reply with JSON only, no markdown fence:
        {"suppliers": [
           {"name": "supplier",
            "reach": "local" | "national" | "international",
            "why": "why this one for these parts",
            "ordering": "practical notes — shipping, minimum order, lead time, account needed",
            "items": [{"part": "what to buy", "qty": "how many",
                       "search": "what to paste into their search box",
                       "code": "order code ONLY if it is in the inventory, else null",
                       "note": "substitutions or things to check"}]}],
         "steps": [{"title": "short imperative",
                    "detail": "what to do and what to watch for",
                    "uses": [{"container": "the part before the slash", "slot": "the part after it", "item": "exact name from the inventory"}]}],
         "cautions": ["the things that hurt you or the device"]}
        """;

    private final PartIndex index;
    private final ContainerRegistry registry;
    private final ChatClient chat;
    private final UsageLog usage;
    private final ObjectMapper mapper;
    private final ChatOptions options;
    private final String region;

    public PlanPurchases(
        PartIndex index,
        ContainerRegistry registry,
        ChatClient.Builder builder,
        UsageLog usage,
        ObjectMapper mapper,
        @Value("${rack.ai.ask-model}") String model,
        @Value("${rack.ai.plan-max-tokens}") int maxTokens,
        @Value("${rack.shopping.region}") String region
    ) {
        this.index = index;
        this.registry = registry;
        this.chat = builder.build();
        this.usage = usage;
        this.mapper = mapper;
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
        this.region = region;
    }

    public Plan execute(String project, List<String> needed) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
        if (needed == null || needed.isEmpty()) {
            throw new IllegalArgumentException("nothing to buy — ask what is missing first");
        }

        AskAboutRack.Inventory inventory = AskAboutRack.inventory(registry, index);

        String prompt = "Region: " + region + "\n\n"
            + "Everything already in storage, one item per line, as "
            + "container/slot (a container id and a slot id, split on the slash) | name | "
            + "description | part number | category | quantity | tags:\n\n"
            + String.join("\n", inventory.lines())
            + "\n\nProject: " + project.strip()
            + "\n\nNeeds buying:\n"
            + needed.stream().filter(n -> n != null && !n.isBlank())
                .map(n -> "- " + n.strip()).reduce((a, b) -> a + "\n" + b).orElse("");

        ChatResponse response = chat.prompt().options(options).system(SYSTEM).user(prompt).call().chatResponse();
        recordUsage(response);
        String raw = response.getResult().getOutput().getText();

        Reply reply;
        try {
            reply = mapper.readValue(ModelReply.json(raw), Reply.class);
        } catch (Exception e) {
            log.warn("Could not read the purchase plan: {}", e.toString());
            throw new IllegalStateException("The model's plan could not be read. Try again.", e);
        }

        return new Plan(project.strip(), region,
            clean(reply.suppliers(), inventory),
            verifySteps(reply.steps(), inventory),
            reply.cautions() == null ? List.of() : List.copyOf(reply.cautions()));
    }

    /**
     * Drops an order code the inventory does not vouch for.
     *
     * <p>An order code is the one thing on a shopping list that gets typed
     * straight into a supplier's search box, so a plausible invention wastes a
     * trip in a way a wrong search term does not. It is allowed through only when
     * some item's own text already carries it — that makes it a fact about
     * something on the shelf rather than a recollection.
     */
    private static List<Supplier> clean(List<Supplier> suppliers, AskAboutRack.Inventory inventory) {
        if (suppliers == null) return List.of();
        String haystack = String.join("\n", inventory.lines()).toLowerCase(Locale.ROOT);
        List<Supplier> out = new ArrayList<>();
        for (Supplier s : suppliers) {
            if (s == null || s.name() == null) continue;
            List<Line> lines = new ArrayList<>();
            for (Line l : s.items() == null ? List.<Line>of() : s.items()) {
                if (l == null || l.part() == null) continue;
                lines.add(vouched(l, haystack));
            }
            if (!lines.isEmpty()) {
                out.add(new Supplier(s.name(), s.reach(), s.why(), s.ordering(), List.copyOf(lines)));
            }
        }
        return List.copyOf(out);
    }

    /** Keeps an order code only if some item's own text already carries it. */
    static Line vouched(Line line, String inventoryText) {
        String code = line.code();
        if (code == null || code.isBlank()) return line;
        String needle = code.strip().toLowerCase(Locale.ROOT);
        // Under four characters is evidence of nothing: a listing of 152 items
        // contains every short string, so a match would vouch for anything.
        if (needle.length() >= 4 && inventoryText.contains(needle)) return line;
        log.warn("Dropping an order code the inventory does not carry: \"{}\" for {}", code, line.part());
        return new Line(line.part(), line.qty(), line.search(), null, line.note());
    }

    private static List<Step> verifySteps(List<Step> steps, AskAboutRack.Inventory inventory) {
        if (steps == null) return List.of();
        List<Step> out = new ArrayList<>();
        for (Step s : steps) {
            if (s == null || s.title() == null) continue;
            out.add(new Step(s.title(), s.detail(),
                AskAboutRack.keepReal(s.uses(), inventory.held())));
        }
        return List.copyOf(out);
    }

    private void recordUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        org.springframework.ai.chat.metadata.Usage tokens = metadata == null ? null : metadata.getUsage();
        if (tokens == null) return;
        Integer in = tokens.getPromptTokens();
        Integer out = tokens.getCompletionTokens();
        usage.record(metadata.getModel(), in == null ? 0 : in, out == null ? 0 : out);
    }

    record Reply(List<Supplier> suppliers, List<Step> steps, List<String> cautions) {}

    public record Line(String part, String qty, String search, String code, String note) {}

    public record Supplier(String name, String reach, String why, String ordering, List<Line> items) {}

    public record Step(String title, String detail, List<AskAboutRack.Found> uses) {}

    public record Plan(String project, String region, List<Supplier> suppliers,
                       List<Step> steps, List<String> cautions) {}
}
