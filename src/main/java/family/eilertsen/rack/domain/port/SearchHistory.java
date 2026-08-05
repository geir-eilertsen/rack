package family.eilertsen.rack.domain.port;

import java.util.List;

/**
 * The last few things someone went looking for. You hunt the same drawer
 * repeatedly — batteries, heat shrink, the one connector — so the second trip
 * should be a tap rather than the same typing again.
 */
public interface SearchHistory {

    /** Never throws: remembering a search is not worth failing the search over. */
    void remember(String query);

    /** Most recent first. */
    List<String> recent();
}
