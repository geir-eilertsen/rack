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
}
