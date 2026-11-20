package family.eilertsen.rack.domain.model;

import java.util.Locale;

/**
 * What the list knows about a store beyond its name: whether it is a place
 * you walk into or one you order from, and how to search it.
 *
 * <p><strong>Online</strong> means there is nowhere to stand: no spot to save,
 * no arrival to notice, and a line for it is ordered and then arrives rather
 * than being picked off a shelf. A store nobody has said anything about is a
 * shop with a door — what every store was before there was a choice.
 *
 * <p>{@code searchUrl} is the store's own search with {@code {q}} where the
 * words go — {@code https://www.amazon.de/s?k={q}}. Typed by the owner, never
 * guessed: rack cannot reach the web, so it offers a search the store
 * answers rather than a product page it made up. Only {@code http} and
 * {@code https}, checked here where every path that stores one passes,
 * because it is rendered into an {@code href} and a {@code javascript:}
 * address there runs when tapped.
 */
public record Shop(String name, boolean online, String searchUrl) {

    public static final String QUERY = "{q}";

    public Shop {
        name = name == null ? null : name.strip();
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Say which store");
        if (name.length() > 80) throw new IllegalArgumentException("Store name too long");
        searchUrl = searchUrl == null || searchUrl.isBlank() ? null : searchUrl.strip();
        if (searchUrl != null) {
            if (searchUrl.length() > 500) throw new IllegalArgumentException("Search address too long");
            if (!searchUrl.toLowerCase(Locale.ROOT).matches("https?://[^\\s]+")) {
                throw new IllegalArgumentException("A search address starts with http:// or https://");
            }
            if (!searchUrl.contains(QUERY)) {
                throw new IllegalArgumentException("Put " + QUERY + " in the search address where the words go — "
                    + "search the store for anything and copy the address, replacing what you searched for with " + QUERY);
            }
        }
    }

    public boolean named(String store) {
        return store != null && name.equalsIgnoreCase(store.strip());
    }
}
