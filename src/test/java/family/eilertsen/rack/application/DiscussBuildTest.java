package family.eilertsen.rack.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.UsageLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The model is not tested; the seams either side of it are. The whole
 * conversation and the whole index go in, the model's own earlier turns go
 * back in the shape it answers in, and nothing it says is on the shelf is
 * believed until the index agrees.
 */
class DiscussBuildTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final ContainerId LAB = new ContainerId("lab");

    private FakeIndex index;
    private ContainerRegistry registry;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        registry = new ContainerRegistry(new FakeStore(List.of(
            new Container(RACK, "Skuffereol", ContainerLayout.grid(2, 2), 1.0f, "drawer", null, null),
            new Container(LAB, "Lab", ContainerLayout.linear(3, null), 1.0f, "shelf", null, null))));
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    void aSuggestionTheIndexAgreesWithIsKept() {
        index.put(LAB, slot("3", item("19V laptop power supply", "Dell, 90W, barrel plug", 1)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<DiscussBuild.Suggestion> kept = DiscussBuild.verify(List.of(
            new DiscussBuild.Suggestion("lab", "3", "19V laptop power supply", "adapt",
                "regulate it down to 15 V with a buck module")), held);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).kind()).isEqualTo("adapt");
        assertThat(kept.get(0).how()).contains("buck");
    }

    @Test
    void aSuggestionNamingAThingTheRackDoesNotHoldIsDropped() {
        // The failure this cannot have: sending someone to a drawer for a supply
        // the model remembers from a different workshop. The rack is furnished
        // from the index, never from memory.
        index.put(LAB, slot("3", item("19V laptop power supply", "Dell", 1)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<DiscussBuild.Suggestion> kept = DiscussBuild.verify(List.of(
            new DiscussBuild.Suggestion("lab", "3", "LM317 regulator", "use", "set it to 15 V"),
            new DiscussBuild.Suggestion("rack", "A1", "19V laptop power supply", "use", "as is")), held);

        // The invented regulator goes; the real supply is placed in the one
        // drawer that holds it, whatever drawer was cited.
        assertThat(kept).extracting(DiscussBuild.Suggestion::item).containsExactly("19V laptop power supply");
        assertThat(kept.get(0).container()).isEqualTo("lab");
        assertThat(kept.get(0).slot()).isEqualTo("3");
    }

    @Test
    void aMisPunctuatedCitationIsRepairedIntoADrawer() {
        // Every listing line begins "lab/3 | …", and the model sometimes hands
        // that token back in both fields. Same repair the checklist gets.
        index.put(LAB, slot("3", item("19V laptop power supply", "Dell", 1)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<DiscussBuild.Suggestion> kept = DiscussBuild.verify(List.of(
            new DiscussBuild.Suggestion("lab/3", "lab/3", "19v laptop power supply", "cannibalize", "")), held);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).container()).isEqualTo("lab");
        assertThat(kept.get(0).slot()).isEqualTo("3");
        // And the spelling is the page's, whichever the model used.
        assertThat(kept.get(0).kind()).isEqualTo("cannibalise");
    }

    @Test
    void anUnknownKindIsAPlainUse() {
        assertThat(DiscussBuild.kind("repurpose")).isEqualTo("use");
        assertThat(DiscussBuild.kind(null)).isEqualTo("use");
        assertThat(DiscussBuild.kind(" Substitute ")).isEqualTo("substitute");
    }

    @Test
    void theModelsOwnTurnsGoBackAsTheJsonTheyWere() {
        // A history of plain paragraphs invites a plain paragraph back, and a
        // paragraph is not a reply this can read. Its earlier turns are replayed
        // in the shape it was asked for, suggestions and all.
        DiscussBuild discuss = discuss(new StubModel("{}"));
        List<Message> transcript = discuss.transcript(List.of(
            new DiscussBuild.Turn("user", "I need 15 V DC", null, null),
            new DiscussBuild.Turn("assistant", "The laptop brick would do it.",
                List.of(new DiscussBuild.Suggestion("lab", "3", "19V laptop power supply", "adapt", "buck it")),
                List.of("a buck module")),
            new DiscussBuild.Turn("user", "How much current can that give?", null, null)));

        assertThat(transcript).hasSize(3);
        assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
        assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(transcript.get(1).getText())
            .contains("\"reply\":\"The laptop brick would do it.\"")
            .contains("\"item\":\"19V laptop power supply\"")
            .contains("\"missing\":[\"a buck module\"]");
        assertThat(transcript.get(2).getText()).isEqualTo("How much current can that give?");
    }

    @Test
    void aLongConversationSendsItsTailAndNeverOpensWithTheModel() {
        List<DiscussBuild.Turn> turns = new ArrayList<>();
        for (int i = 0; i < DiscussBuild.MAX_MESSAGES + 3; i++) {
            turns.add(new DiscussBuild.Turn(i % 2 == 0 ? "user" : "assistant", "turn " + i, null, null));
        }
        // 43 turns, the tail of 40 starts at turn 3, which is an assistant's.

        List<Message> transcript = discuss(new StubModel("{}")).transcript(turns);

        assertThat(transcript.size()).isLessThanOrEqualTo(DiscussBuild.MAX_MESSAGES);
        assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
        assertThat(transcript.get(transcript.size() - 1).getText()).isEqualTo("turn 42");
    }

    @Test
    void theWholeShelfAndTheWholeConversationGoToTheModel() {
        index.put(LAB, slot("3", item("19V laptop power supply", "Dell", 1)));
        index.put(RACK, slot("A1", item("LM2596 buck module", "adjustable", 3)));
        StubModel model = new StubModel("""
            Here you go:
            {"reply": "Use the Dell brick and drop it with the buck module.",
             "suggestions": [
               {"container": "lab", "slot": "3", "item": "19V laptop power supply", "kind": "adapt", "how": "feeds the buck"},
               {"container": "rack", "slot": "A1", "item": "LM2596 buck module", "kind": "use", "how": "set to 15 V"},
               {"container": "rack", "slot": "A2", "item": "LM317", "kind": "use", "how": "invented"}],
             "missing": ["a case", ""]}
            """);

        DiscussBuild.Answer answer = discuss(model).execute(List.of(
            new DiscussBuild.Turn("user", "I need 15 V DC for a small amp", null, null)));

        // The listing is in the system text, and every drawer is in it.
        assertThat(model.lastPrompt.getSystemMessage().getText())
            .contains("lab/3 | 19V laptop power supply")
            .contains("rack/A1 | LM2596 buck module");
        assertThat(model.lastPrompt.getUserMessage().getText()).isEqualTo("I need 15 V DC for a small amp");
        // The prose survives its preamble, the real suggestions are kept, the
        // invented one is not, and a blank "missing" entry is not a thing to buy.
        assertThat(answer.reply()).startsWith("Use the Dell brick");
        assertThat(answer.suggestions()).extracting(DiscussBuild.Suggestion::item)
            .containsExactly("19V laptop power supply", "LM2596 buck module");
        assertThat(answer.missing()).containsExactly("a case");
        assertThat(answer.itemsConsidered()).isEqualTo(2);
    }

    @Test
    void anEmptyIndexIsSaidRatherThanAsked() {
        StubModel model = new StubModel("{}");
        DiscussBuild.Answer answer = discuss(model).execute(List.of(
            new DiscussBuild.Turn("user", "I need 15 V DC", null, null)));

        assertThat(model.lastPrompt).isNull();
        assertThat(answer.reply()).contains("nothing in the index");
        assertThat(answer.itemsConsidered()).isZero();
    }

    @Test
    void theLastMessageMustBeTheUsers() {
        DiscussBuild discuss = discuss(new StubModel("{}"));
        assertThatThrownBy(() -> discuss.execute(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discuss.execute(List.of(
                new DiscussBuild.Turn("user", "hi", null, null),
                new DiscussBuild.Turn("assistant", "hello", null, null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discuss.execute(List.of(new DiscussBuild.Turn("user", "  ", null, null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private DiscussBuild discuss(ChatModel model) {
        return new DiscussBuild(index, registry, ChatClient.builder(model), new NoUsageLog(),
            mapper, "test-model", 1000);
    }

    private static final class NoUsageLog implements UsageLog {
        @Override
        public void record(String model, long inputTokens, long outputTokens) {
        }

        @Override
        public Map<String, family.eilertsen.rack.domain.model.Usage> byModel() {
            return Map.of();
        }
    }

    /** Answers with a fixed reply and remembers what it was asked. */
    private static final class StubModel implements ChatModel {
        private final String reply;
        Prompt lastPrompt;

        StubModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private static Slot slot(String id, Item... items) {
        return new Slot(new SlotId(id), List.of(items), Instant.now(), null);
    }

    private static Item item(String name, String description, int qty) {
        return new Item(name, description, null, "other", qty, 0.9, List.of(), List.of(), null, null, List.of());
    }

    private record FakeStore(List<Container> containers) implements ContainerStore {
        @Override
        public List<Container> loadAll() {
            return containers;
        }

        @Override
        public void saveAll(List<Container> containers) {
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        void put(ContainerId container, Slot slot) {
            slots.computeIfAbsent(container, k -> new LinkedHashMap<>()).put(slot.id(), slot);
        }

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.ofNullable(slots.getOrDefault(container, Map.of()).get(slot));
        }

        @Override
        public void save(ContainerId container, Slot slot) {
            put(container, slot);
        }

        @Override
        public Collection<Slot> all(ContainerId container) {
            return new ArrayList<>(slots.getOrDefault(container, Map.of()).values());
        }

        @Override
        public void forget(ContainerId container) {
            slots.remove(container);
        }

        @Override
        public Set<String> documentsInUse() {
            return Set.of();
        }

        @Override
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return List.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
