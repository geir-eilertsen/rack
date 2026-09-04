package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Claim;
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

        These are the entries to place, one per line, each beginning with its
        subject number:
        %s

        This is everything else they have, one per line, each beginning with
        its reference:
        %s

        For each subject: which of those entries does it BELONG WITH — the
        other half of a pair, where one is not much use without the other? A
        charger and the device it charges, either way round. A remote and its
        receiver. A lens and its cap. A battery and the tool it powers. A dock
        and the laptop it docks. A HAT and the Raspberry Pi it plugs onto.
        Read the descriptions: a 5.1V 3A USB-C power supply is a Raspberry Pi
        4's, and a Sony charger belongs with the Sony phone, not with every
        phone.

        Rules:
        - Consider only entries from the list, by their exact reference.
        - Give every candidate you consider a verdict, and be strict about it:
          "pair" — one is not much use without the other, made for it or
          serving it; "same kind" — another charger, cable, adapter or
          computer like this one, which is never a pair; "related" — same
          category, same hobby, same brand, plugs into the same socket, but
          neither needs the other. Only "pair" will be kept, so a candidate
          you would talk yourself out of belongs under one of the other two.
        - A brand in common is not a pair. A spare part or component belongs
          only with the exact product it fits, and only when that product is
          the other entry: a spring for a light switch is not the pair of a
          USB outlet of the same make.
        - Made for one thing, or good for many: both are real. A Sony CST-13
          charger belongs with the Sony phone it was made for and nothing
          else. A general-purpose USB-C charger, a AA battery or an HDMI cable
          belongs with every entry it serves, so cite each of them.
        - "why" says what the one does for the other, in a few words. Not a
          description of the entry.
        - Most things belong with nothing. An empty array is the usual answer.

        Reply with a JSON array only, no prose, no code fence:
        [{"subject": "S1", "ref": "the reference", "verdict": "pair" | "same kind" | "related", "why": "what the one does for the other"}]
        """;

    private static final String CONFIRM_PROMPT = """
        You are checking claims about what belongs together in someone's
        inventory of drawers and boxes. Each claim is two entries and a
        proposed reason. Say whether the two are really the other half of a
        pair: one is not much use without the other, made for it or serving
        it. Be strict:
        - A brand in common is not a pair. A spare part or component belongs
          only with the exact product it fits, and only when that product is
          the other entry. A spring for a light switch is not the pair of a
          USB wall outlet of the same make; the outlet has no switch.
        - Another of the same kind is not a pair. Same category, same hobby,
          plugs into the same socket: not a pair.
        - A device and the charger, cable, battery, cap, remote or add-on
          board made for it, or a general-purpose one that serves it: a pair.

        The claims:
        %s

        Reply with a JSON array only, one per claim in order, no prose, no
        code fence:
        [{"claim": 1, "pair": true, "why": "a few words"}]
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
    public List<Companion> find(List<String> subjects, List<String> listing) {
        if (subjects == null || subjects.isEmpty() || listing == null || listing.isEmpty()) return List.of();

        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < subjects.size(); i++) {
            numbered.append("S").append(i + 1).append(" | ").append(subjects.get(i).strip()).append('\n');
        }
        String prompt = PROMPT.formatted(numbered.toString().strip(), String.join("\n", listing));
        String reply;
        try {
            reply = SpringAi.tally(chat.prompt().options(options).user(prompt).call().chatResponse(), usage);
        } catch (RuntimeException e) {
            // No API key, rate limit, network — the page says it could not look.
            log.warn("Pair lookup unavailable for {}: {}", subjects, e.toString());
            return List.of();
        }

        try {
            List<Cited> cited = mapper.readValue(SpringAi.json(reply), new TypeReference<>() {});
            return pairs(cited, subjects.size());
        } catch (Exception e) {
            log.warn("Pair lookup returned non-JSON for {}: {}", subjects, reply);
            return List.of();
        }
    }

    @Override
    public List<Boolean> confirm(List<Claim> claims) {
        if (claims == null || claims.isEmpty()) return List.of();
        List<Boolean> stands = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) stands.add(true);

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < claims.size(); i++) {
            Claim c = claims.get(i);
            list.append(i + 1).append(". A: ").append(c.subject().strip())
                .append("\n   B: ").append(c.candidate().strip())
                .append("\n   proposed: ").append(c.why() == null ? "" : c.why().strip()).append('\n');
        }
        String prompt = CONFIRM_PROMPT.formatted(list.toString().strip());
        String reply;
        try {
            reply = SpringAi.tally(chat.prompt().options(options).user(prompt).call().chatResponse(), usage);
        } catch (RuntimeException e) {
            log.warn("Pair confirmation unavailable: {}", e.toString());
            return stands;
        }
        try {
            List<Verdict> verdicts = mapper.readValue(SpringAi.json(reply), new TypeReference<>() {});
            return held(verdicts, claims.size());
        } catch (Exception e) {
            log.warn("Pair confirmation returned non-JSON: {}", reply);
            return stands;
        }
    }

    /** A claim the model did not answer does not stand: it was asked. */
    static List<Boolean> held(List<Verdict> verdicts, int claims) {
        List<Boolean> stands = new ArrayList<>();
        for (int i = 0; i < claims; i++) stands.add(false);
        for (Verdict v : verdicts) {
            if (v == null || v.claim() == null) continue;
            int i = v.claim() - 1;
            if (i >= 0 && i < claims) stands.set(i, Boolean.TRUE.equals(v.pair()));
        }
        return stands;
    }

    record Verdict(Integer claim, Boolean pair, String why) {}

    /**
     * Only what the model called a pair. Asked for citations alone, Sonnet
     * cited the ceiling rose beside a USB wall outlet and wrote "merely
     * related, not a pair" in the reason — it knew, and the format gave it no
     * way to say so. A verdict per candidate is that way.
     */
    static List<Companion> pairs(List<Cited> cited, int subjects) {
        List<Companion> companions = new ArrayList<>();
        for (Cited c : cited) {
            if (c == null || c.ref() == null || c.ref().isBlank()) continue;
            if (c.verdict() == null || !c.verdict().strip().equalsIgnoreCase("pair")) continue;
            int subject = subjectIndex(c.subject(), subjects);
            if (subject < 0) continue;
            companions.add(new Companion(subject, c.ref().strip(), c.why() == null ? "" : c.why().strip()));
        }
        return List.copyOf(companions);
    }

    /** "S1" is the first subject; one subject may be cited without saying which. */
    private static int subjectIndex(String s, int subjects) {
        if (s == null || s.isBlank()) return subjects == 1 ? 0 : -1;
        String digits = s.strip().replaceAll("^[Ss]", "");
        try {
            int n = Integer.parseInt(digits) - 1;
            return n >= 0 && n < subjects ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    record Cited(String subject, String ref, String verdict, String why) {}
}
