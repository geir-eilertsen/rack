package family.eilertsen.rack.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.application.AskAboutRack.Found;
import family.eilertsen.rack.application.AskAboutRack.Inventory;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.UsageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Talking a build through against what is on the shelf: "I need a 15 V supply
 * for a small amplifier" — and the answer is the laptop brick in lab/3 with a
 * buck module from rack/C4, or the transformer inside the dead amplifier in the
 * garage box, and which of those is the sensible one.
 *
 * <p>{@link AskAboutRack} answers one question with a checklist: does the rack
 * hold what a known job needs. This is the other conversation, the one that
 * happens before the job is known — what could I make this out of, is that a
 * good idea, what if I did it the other way. It is a conversation, so the
 * whole exchange so far goes back with every message, and the model builds on
 * what was said rather than starting again.
 *
 * <p>The same two rules hold. The whole index goes in — the point of asking
 * what could be cannibalised is that the answer may be a thing nobody would
 * search for, and a retrieval step would drop exactly that. And nothing the
 * model says they have is believed until the index agrees: every suggestion
 * names a drawer and an item, and {@link #verify} drops the ones that drawer
 * does not hold, through the same check the checklist's citations pass.
 *
 * <p>Nothing is stored. A discussion is a proposal under review, the way a plan
 * is, and it lives in the browser until something comes of it.
 */
@Service
public class DiscussBuild {

    private static final Logger log = LoggerFactory.getLogger(DiscussBuild.class);

    /** Enough for a long think at a bench; the listing is the bulk of every call. */
    static final int MAX_MESSAGES = 40;

    static final Set<String> KINDS = Set.of("use", "adapt", "cannibalise", "substitute");

    private static final String SYSTEM = """
        You are a workshop partner for someone with a store of small parts, tools
        and salvage. They say what they are trying to build or fix; you help them
        do it with what they already have, and say honestly what they still need.

        Below is the complete contents of their storage — every item, with the
        container and slot it is in. It is complete: if something is not listed,
        they do not have it.

        What they bring is the job. What you bring is knowing how parts work and
        what a part can be turned into: a 19 V laptop supply and a buck module
        make a 15 V supply; a broken amplifier is a transformer, a heat sink and
        a handful of capacitors; a dead printer is stepper motors and steel rods.
        Look for those. Offer the straightforward use first, then the adaptation,
        then what could be taken apart — and say which is which, because
        cannibalising something is a decision to make with open eyes.

        Rules that matter:
        - Never suggest something they have unless it is in the list. Every
          suggestion must quote an item's exact name and its real container and
          slot from the list.
        - Say what the compromise is: a rating that is close but not right, a
          part that needs reworking, a count that is an estimate from a photo.
        - Mains voltage and lithium cells are dangerous. Say so where it applies,
          briefly.
        - If the brief is too thin to help — a voltage with no current, "a
          light" with no idea where — ask the one or two questions that would
          change the answer, and suggest what you can meanwhile.
        - This is a conversation at a bench, not a report. A few sentences; the
          suggestions carry the detail. Build on what was said earlier in the
          conversation rather than starting over, and do not repeat suggestions
          already made unless something about them has changed.

        Reply with JSON only, no markdown fence:
        {"reply": "what you would say, plain text, a few sentences",
         "suggestions": [
           {"container": "the part before the slash", "slot": "the part after it",
            "item": "exact name from the list",
            "kind": "use" | "adapt" | "cannibalise" | "substitute",
            "how": "what it would do for this build, and the compromise if any"}],
         "missing": ["what they would still need to get, one per entry — or empty"]}

        Everything in storage, one item per line, as container/slot (a container
        id and a slot id, split on the slash) | name | description | part number
        | category | quantity | tags:

        """;

    private final PartIndex index;
    private final ContainerRegistry registry;
    private final ChatClient chat;
    private final UsageLog usage;
    private final ObjectMapper mapper;
    private final ChatOptions options;

    public DiscussBuild(
        PartIndex index,
        ContainerRegistry registry,
        ChatClient.Builder builder,
        UsageLog usage,
        ObjectMapper mapper,
        @Value("${rack.ai.ask-model}") String model,
        @Value("${rack.ai.discuss-max-tokens}") int maxTokens
    ) {
        this.index = index;
        this.registry = registry;
        this.chat = builder.build();
        this.usage = usage;
        this.mapper = mapper;
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    /**
     * The next reply in a conversation, given the whole of it so far. The last
     * message must be the user's: it is the one being answered.
     */
    public Answer execute(List<Turn> conversation) {
        List<Turn> turns = conversation == null ? List.of() : conversation;
        if (turns.isEmpty() || !"user".equals(turns.get(turns.size() - 1).role())
            || turns.get(turns.size() - 1).text() == null
            || turns.get(turns.size() - 1).text().isBlank()) {
            throw new IllegalArgumentException("a message to answer is required");
        }

        Inventory inventory = AskAboutRack.inventory(registry, index);
        if (inventory.lines().isEmpty()) {
            return new Answer("There is nothing in the index yet, so there is nothing to build from. "
                + "File a few drawers first.", List.of(), List.of(), 0);
        }

        ChatResponse response = chat.prompt().options(options)
            .system(SYSTEM + String.join("\n", inventory.lines()))
            .messages(transcript(turns))
            .call().chatResponse();
        recordUsage(response);
        String raw = response.getResult().getOutput().getText();

        Reply reply;
        try {
            reply = mapper.readValue(ModelReply.json(raw), Reply.class);
        } catch (Exception e) {
            log.warn("Could not read the reply about the build: {} — reply began: {}",
                e.toString(), head(raw));
            throw new IllegalStateException("The model's reply could not be read. Try again.", e);
        }

        return new Answer(reply.reply() == null ? "" : reply.reply().strip(),
            verify(reply.suggestions(), inventory.held()),
            reply.missing() == null ? List.of() : reply.missing().stream()
                .filter(m -> m != null && !m.isBlank()).map(String::strip).toList(),
            inventory.lines().size());
    }

    /**
     * The conversation as the model sees it. Its own earlier turns go back as
     * the JSON they were, not as the prose they were drawn as, so the shape it
     * is asked for is the shape it has been answering in all along — a history
     * of plain paragraphs invites a plain paragraph back. Only the tail is sent
     * when a conversation runs long; the listing is the bulk of every call and
     * an hour's chat is small beside it, but not unbounded.
     */
    List<Message> transcript(List<Turn> turns) {
        List<Turn> tail = turns.size() <= MAX_MESSAGES
            ? turns : turns.subList(turns.size() - MAX_MESSAGES, turns.size());
        List<Message> messages = new ArrayList<>();
        for (Turn t : tail) {
            if (t == null || t.text() == null || t.text().isBlank()) continue;
            if ("assistant".equals(t.role())) {
                messages.add(new AssistantMessage(replay(t)));
            } else {
                messages.add(new UserMessage(t.text().strip()));
            }
        }
        // Two user turns in a row are fine for the API; an assistant turn first
        // is not, and a stray one can only come from a tail cut mid-exchange.
        while (!messages.isEmpty() && messages.get(0) instanceof AssistantMessage) {
            messages.remove(0);
        }
        return messages;
    }

    private String replay(Turn t) {
        try {
            return mapper.writeValueAsString(new Reply(t.text(),
                t.suggestions() == null ? List.of() : t.suggestions(),
                t.missing() == null ? List.of() : t.missing()));
        } catch (Exception e) {
            return t.text();
        }
    }

    /**
     * The suggestions the index agrees with, each placed in the drawer it really
     * means. A suggestion is a claim of possession with a use attached, so it
     * passes through {@link AskAboutRack#keepReal} exactly as a checklist
     * citation does: the citation is repaired where it is only mis-punctuated,
     * and dropped where no drawer holds that item.
     */
    static List<Suggestion> verify(List<Suggestion> suggestions, Map<String, List<String>> held) {
        List<Suggestion> real = new ArrayList<>();
        for (Suggestion s : suggestions == null ? List.<Suggestion>of() : suggestions) {
            if (s == null || s.item() == null) continue;
            List<Found> placed = AskAboutRack.keepReal(
                List.of(new Found(s.container(), s.slot(), s.item(), s.how())), held);
            if (placed.isEmpty()) continue;
            Found f = placed.get(0);
            real.add(new Suggestion(f.container(), f.slot(), s.item(), kind(s.kind()), s.how()));
        }
        return List.copyOf(real);
    }

    /** One of the four, spelled the way the page knows; anything else is a plain use. */
    static String kind(String kind) {
        String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
        if (k.equals("cannibalize")) k = "cannibalise";
        return KINDS.contains(k) ? k : "use";
    }

    private static String head(String raw) {
        String s = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private void recordUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        org.springframework.ai.chat.metadata.Usage tokens = metadata == null ? null : metadata.getUsage();
        if (tokens == null) return;
        Integer in = tokens.getPromptTokens();
        Integer out = tokens.getCompletionTokens();
        usage.record(metadata.getModel(), in == null ? 0 : in, out == null ? 0 : out);
    }

    /** What the model replies with, before any of it is believed. */
    record Reply(String reply, List<Suggestion> suggestions, List<String> missing) {}

    /**
     * One message of the conversation, as the browser keeps it. An assistant
     * turn carries what it suggested, so it can be replayed as it was said.
     */
    public record Turn(String role, String text, List<Suggestion> suggestions, List<String> missing) {}

    /** A thing on the shelf and what it could do for this build. */
    public record Suggestion(String container, String slot, String item, String kind, String how) {}

    /** {@code itemsConsidered} is the whole index, every time, and says so. */
    public record Answer(String reply, List<Suggestion> suggestions, List<String> missing,
                         @JsonProperty("items_considered") int itemsConsidered) {}
}
