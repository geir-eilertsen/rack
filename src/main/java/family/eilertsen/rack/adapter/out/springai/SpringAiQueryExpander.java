package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.port.QueryExpander;
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
    private static final int MAX_VOCABULARY = 400;
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

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final ChatOptions options;

    public SpringAiQueryExpander(
        ChatClient.Builder builder,
        ObjectMapper mapper,
        @Value("${rack.search.expansion-model}") String model,
        @Value("${rack.search.expansion-max-tokens}") int maxTokens
    ) {
        this.chat = builder.build();
        this.mapper = mapper;
        // Synonyms are a small, fast job — a cheaper model than the vision
        // extractor keeps a mid-typing search from waiting on the big one.
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    @Override
    public List<String> expand(String query, Collection<String> vocabulary) {
        if (query == null || query.isBlank()) return List.of();

        String prompt = PROMPT.formatted(query.strip(), vocabularyList(vocabulary), MAX_TERMS);
        String reply;
        try {
            reply = chat.prompt().options(options).user(prompt).call().content();
        } catch (RuntimeException e) {
            // No API key, rate limit, network — search still has its literal hits.
            log.warn("Query expansion unavailable for \"{}\": {}", query, e.toString());
            return List.of();
        }

        try {
            List<String> terms = mapper.readValue(SpringAi.stripCodeFences(reply), new TypeReference<>() {});
            return clean(terms, query);
        } catch (Exception e) {
            log.warn("Query expansion returned non-JSON for \"{}\": {}", query, reply);
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
