package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Companion;
import family.eilertsen.rack.domain.port.PairFinder;
import family.eilertsen.rack.domain.port.UsageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiPairFinder implements PairFinder {

    private static final Logger log = LoggerFactory.getLogger(SpringAiPairFinder.class);

    private static final String PROMPT = """
        Someone keeps an inventory of what is in their drawers and boxes —
        electronics, tools, cables, chargers, household things — one entry per
        thing, each with where it is.

        This is the entry to place:
        %s

        This is everything else they have, one per line, each beginning with
        its reference:
        %s

        Which of those entries does this thing BELONG WITH — the other half of
        a pair, where one is not much use without the other? A charger and the
        device it charges, either way round. A remote and its receiver. A lens
        and its cap. A battery and the tool it powers. A dock and the laptop
        it docks. Read the descriptions: a 5.1V 3A USB-C power supply is a
        Raspberry Pi 4's, and a Sony charger belongs with the Sony phone, not
        with every phone.

        Rules:
        - Cite only entries from the list, by their exact reference.
        - Never another of the same kind: a second charger is not what a
          charger belongs with. Never something merely related or in the same
          category.
        - Made for one thing, or good for many: both are real. A Sony CST-13
          charger belongs with the Sony phone it was made for and nothing
          else. A general-purpose USB-C charger, a AA battery or an HDMI cable
          belongs with every entry it serves, so cite each of them, and say in
          "why" what it does for that one.
        - Most things belong with nothing. An empty array is the usual answer.

        Reply with a JSON array only, no prose, no code fence:
        [{"ref": "the reference", "why": "a few words on what the one does for the other"}]
        """;

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final UsageLog usage;
    private final ChatOptions options;

    public SpringAiPairFinder(
        ChatClient.Builder builder,
        ObjectMapper mapper,
        UsageLog usage,
        @Value("${rack.ai.companions-model}") String model,
        @Value("${rack.ai.companions-max-tokens}") int maxTokens
    ) {
        this.chat = builder.build();
        this.mapper = mapper;
        this.usage = usage;
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    @Override
    public List<Companion> find(String subject, List<String> listing) {
        if (subject == null || subject.isBlank() || listing == null || listing.isEmpty()) return List.of();

        String prompt = PROMPT.formatted(subject.strip(), String.join("\n", listing));
        String reply;
        try {
            reply = SpringAi.tally(chat.prompt().options(options).user(prompt).call().chatResponse(), usage);
        } catch (RuntimeException e) {
            // No API key, rate limit, network — the page says it could not look.
            log.warn("Pair lookup unavailable for \"{}\": {}", subject, e.toString());
            return List.of();
        }

        try {
            List<Cited> cited = mapper.readValue(SpringAi.json(reply), new TypeReference<>() {});
            List<Companion> companions = new ArrayList<>();
            for (Cited c : cited) {
                if (c == null || c.ref() == null || c.ref().isBlank()) continue;
                companions.add(new Companion(c.ref().strip(), c.why() == null ? "" : c.why().strip()));
            }
            return List.copyOf(companions);
        } catch (Exception e) {
            log.warn("Pair lookup returned non-JSON for \"{}\": {}", subject, reply);
            return List.of();
        }
    }

    private record Cited(String ref, String why) {}
}
