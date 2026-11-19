package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * Where a shop is, saved while standing in it. The page compares the phone's
 * position against these and opens the list on the store it is in.
 *
 * <p>Learned rather than looked up: a lookup would send the phone's position
 * to a map service, and no map service knows that this household calls Leroy
 * Merlin "Leroy". Each branch is its own spot under the same store name.
 *
 * <p>{@code accuracy} is the phone's own estimate in metres when it was saved —
 * a spot saved with a 300 m fix is a weaker claim than one saved at 10 m.
 */
public record ShopPlace(String id, String store, double lat, double lon, Double accuracy, Instant savedAt) {
    public ShopPlace {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,40}")) throw new IllegalArgumentException("Bad place id: " + id);
        store = store == null ? null : store.trim();
        if (store == null || store.isEmpty()) throw new IllegalArgumentException("Say which store this is");
        if (store.length() > 80) throw new IllegalArgumentException("Too long");
        if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) {
            throw new IllegalArgumentException("Not a position: " + lat + ", " + lon);
        }
        if (accuracy != null && !(accuracy >= 0)) accuracy = null;
    }
}
