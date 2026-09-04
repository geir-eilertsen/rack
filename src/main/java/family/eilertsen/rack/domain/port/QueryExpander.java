package family.eilertsen.rack.domain.port;

import java.util.Collection;
import java.util.List;

/**
 * Bridges the words a user searches with the words the index actually uses —
 * "isolating tape" has to find the electrical tape.
 *
 * <p>The vocabulary is passed in rather than guessed at, so the expansion is
 * grounded in what this rack really holds instead of a generic synonym list.
 */
public interface QueryExpander {

    /**
     * Extra terms to search for, in descending order of confidence. Never null;
     * empty when nothing useful can be added, including when the model is
     * unreachable — search must degrade to plain keyword matching, not fail.
     */
    List<String> expand(String query, Collection<String> vocabulary);

    /**
     * The other half of a pair: what this item is used <em>with</em>, in the
     * words this rack uses for it — a phone's charger, a remote's receiver, a
     * lens's cap. Not synonyms, which is {@link #expand}'s job, and not merely
     * related things; a counterpart is something one is not much use without.
     * Same contract as expand: never null, empty when nothing fits or the model
     * is unreachable.
     */
    List<String> goesWith(String name, Collection<String> vocabulary);
}
