package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Item;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether two readings describe the same physical thing.
 *
 * <p>Asked in two places for the same reason. A resync lines a fresh reading of
 * a drawer up against what is recorded; filing more of something into a drawer
 * that already holds it needs to notice before the slot ends up with two rows
 * of AA batteries instead of one. Both are the question "have I seen this
 * already", and both fail the same way if answered carelessly.
 */
final class ItemMatch {

    /**
     * How much of the shorter label's wording two readings must share to be the
     * same thing. Both sides came from the same model reading the same object on
     * different days, so most words survive ("bag of M4 bolts" against "M4 hex
     * bolts"); measuring against the <em>shorter</em> side is what stops a long
     * description from diluting a four-word name into a miss.
     */
    static final double SAME_ITEM = 0.5;

    /** A part number is an identity, not a resemblance, so it outranks any wording overlap. */
    static final double BY_PART_NUMBER = 2.0;

    private ItemMatch() {}

    /** Zero when they are different things; higher is a better pair. */
    static double score(Item held, Item found) {
        if (bothNumbered(held, found)) {
            // A drawer of parts is full of near-identical names: "BC547
            // transistor" and "BC557 transistor" share half their words, and
            // "100K resistors" and "82K resistors" share more. Where both sides
            // carry a part number it is the whole answer — equal is the same
            // part, different is a different part, and no amount of name
            // overlap should be allowed to argue otherwise.
            return samePartNumber(held, found) ? BY_PART_NUMBER : 0;
        }
        double overlap = overlap(words(label(held)), words(label(found)));
        return overlap >= SAME_ITEM ? overlap : 0;
    }

    static boolean sameThing(Item held, Item found) {
        return score(held, found) > 0;
    }

    private static boolean bothNumbered(Item held, Item found) {
        return notBlank(held.partNumber()) && notBlank(found.partNumber());
    }

    private static boolean samePartNumber(Item held, Item found) {
        return bothNumbered(held, found)
            && held.partNumber().trim().equalsIgnoreCase(found.partNumber().trim());
    }

    /** Items catalogued before name and description were split have only the description. */
    private static String label(Item item) {
        return notBlank(item.name()) ? item.name() : item.description();
    }

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) return words;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    private static double overlap(Set<String> held, Set<String> found) {
        if (held.isEmpty() || found.isEmpty()) return 0;
        int common = 0;
        for (String word : held) if (found.contains(word)) common++;
        return (double) common / Math.min(held.size(), found.size());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
