package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.port.QueryExpander;
import family.eilertsen.rack.domain.port.UsageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SpringAiQueryExpander implements QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(SpringAiQueryExpander.class);

    /** Enough vocabulary to recognise the drawer's wording, short enough to stay a small prompt. */
    /**
     * A ceiling on the prompt, not a sample of the rack.
     *
     * <p>400 was under this rack's 658 words, so a quarter of them never reached
     * the model — and the search's headline case, "isolating tape" finding the
     * electrical tape, depended on which side of the cut the word landed. At
     * haiku rates the whole list is about a quarter of a cent a call, and the
     * call only happens when the literal search already failed. The cap is a
     * guard against a rack ten times this size, not a budget.
     */
    private static final int MAX_VOCABULARY = 1500;
    private static final int MAX_TERMS = 6;

    private static final String PROMPT = """
        Someone is searching a small-parts inventory — electronics, fasteners,
        tools, cables, consumables — for something they want to find in a drawer.

        Their query: "%s"

        These are the words the inventory actually uses (item names, categories
        and tags):
        %s

        Return a JSON array of at most %d additional search terms that would find
        what they mean. Rules:
        - Prefer wording that appears in the list above. The point is to bridge
          the searcher's words to the words this inventory uses — someone asking
          for "isolating tape" wants the electrical tape.
        - Include the common trade name, the everyday name, and the technical
          name for the same thing.
        - If the query is not English (Norwegian, say), include the English term.
        - Terms are matched as substrings, so keep them short — one or two words.
        - Every term must add a word the query does not already have. The search
          has tried the query's own words; "tape" is not a widening of
          "isolating tape".
        - Do not invent a term just to fill the array. An empty array is a fine
          answer when the query is already the word this inventory uses.

        Return ONLY the JSON array. No prose, no markdown, no code fences.
        """;

    private static final String GOES_WITH_PROMPT = """
        Someone keeps a small-parts inventory — electronics, fasteners, tools,
        cables, consumables, household things — with one entry per thing in a
        drawer or box.

        This entry: "%s"

        These are the words the inventory actually uses (item names, categories
        and tags):
        %s

        Return a JSON array of at most %d entries from the list above that this
        thing is used TOGETHER WITH — the other half of a pair, where one is not
        much use without the other: a phone and its charger, a remote and its
        receiver, a lens and its cap, a drill and its bits, a printer and its
        cartridges. Rules:
        - Only wording that appears in the list above. The point is to find a
          counterpart that is actually on a shelf here, not to imagine one.
        - Never a synonym or another of the same thing. A second charger is not
          a counterpart of a charger; a "USB cable" is not a counterpart of a
          "USB-C cable".
        - Never something merely related or of the same category. A resistor is
          not a counterpart of a capacitor.
        - Terms are matched as substrings, so keep them short — one or two words.
        - An empty array is the right answer for most things. Do not fill it.

        Return ONLY the JSON array. No prose, no markdown, no code fences.
        """;

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final UsageLog usage;
    private final ChatOptions options;

    public SpringAiQueryExpander(
        ChatClient.Builder builder,
        ObjectMapper mapper,
        UsageLog usage,
        @Value("${rack.ai.expansion-model}") String model,
        @Value("${rack.ai.expansion-max-tokens}") int maxTokens
    ) {
        this.chat = builder.build();
        this.mapper = mapper;
        this.usage = usage;
        // Synonyms are a small, fast job — a cheaper model than the vision
        // extractor keeps a mid-typing search from waiting on the big one.
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    @Override
    public List<String> expand(String query, Collection<String> vocabulary) {
        return ask("Query expansion", PROMPT, query, vocabulary);
    }

    /**
     * Same call, same grounding, same cleaning, a different question: not
     * "what else is this called" but "what is this used with". The cleaning
     * rules carry over unchanged — a counterpart built only from the item's own
     * words ("charger" for "USB charger") would find the item itself.
     */
    @Override
    public List<String> goesWith(String name, Collection<String> vocabulary) {
        return ask("Counterpart lookup", GOES_WITH_PROMPT, name, vocabulary);
    }

    private List<String> ask(String what, String template, String query, Collection<String> vocabulary) {
        if (query == null || query.isBlank()) return List.of();

        String prompt = template.formatted(query.strip(), vocabularyList(vocabulary), MAX_TERMS);
        String reply;
        try {
            reply = SpringAi.tally(chat.prompt().options(options).user(prompt).call().chatResponse(), usage);
        } catch (RuntimeException e) {
            // No API key, rate limit, network — search still has its literal hits.
            log.warn("{} unavailable for \"{}\": {}", what, query, e.toString());
            return List.of();
        }

        try {
            List<String> terms = mapper.readValue(SpringAi.json(reply), new TypeReference<>() {});
            List<String> cleaned = clean(terms, query);
            // A widening that widens nothing is the design working — a term built
            // only from words the query already had puts the noise back. But
            // silently it is indistinguishable from a call that never happened,
            // and "isolating tape" quietly returning nothing is worth being able
            // to look up rather than guess at.
            if (cleaned.isEmpty() && !terms.isEmpty()) {
                log.info("{} for \"{}\" brought no new words: {}", what, query, terms);
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("{} returned non-JSON for \"{}\": {}", what, query, reply);
            return List.of();
        }
    }

    private static String vocabularyList(Collection<String> vocabulary) {
        if (vocabulary == null || vocabulary.isEmpty()) return "(the inventory is empty)";
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (String word : vocabulary) {
            if (shown++ == MAX_VOCABULARY) break;
            list.append("- ").append(word).append('\n');
        }
        if (vocabulary.size() > MAX_VOCABULARY) {
            // Silently sending part of the rack is how this broke: the missing
            // words are exactly the ones a query would have needed bridging to.
            log.warn("Vocabulary is {} words and only {} were sent — raise MAX_VOCABULARY",
                vocabulary.size(), MAX_VOCABULARY);
        }
        return list.toString();
    }

    static List<String> clean(List<String> terms, String query) {
        String original = query.strip().toLowerCase(Locale.ROOT);
        Set<String> alreadySearched = new HashSet<>(Arrays.asList(original.split("\\s+")));
        List<String> cleaned = new ArrayList<>();
        for (String term : terms) {
            if (term == null) continue;
            String t = term.strip();
            String key = t.toLowerCase(Locale.ROOT);
            if (t.length() < 2 || key.equals(original)) continue;
            if (addsNoNewWord(key, alreadySearched)) continue;
            if (cleaned.stream().noneMatch(c -> c.equalsIgnoreCase(t))) cleaned.add(t);
            if (cleaned.size() == MAX_TERMS) break;
        }
        return List.copyOf(cleaned);
    }

    /**
     * "tape" is not a widening of "isolating tape". The literal pass already
     * searched every word of the query and required all of them; a term built
     * only from those same words just drops the one that made it specific, and
     * a single generic word has no second word left for that rule to bite on —
     * which is how twenty-two resistors on tape reels came back.
     */
    private static boolean addsNoNewWord(String term, Set<String> alreadySearched) {
        for (String word : term.split("\\s+")) {
            if (!alreadySearched.contains(word)) return false;
        }
        return true;
    }
}
