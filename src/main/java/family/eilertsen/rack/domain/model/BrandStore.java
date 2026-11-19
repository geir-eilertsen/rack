package family.eilertsen.rack.domain.model;

/**
 * A house brand and the store that sells it: Anslut is Jula's, Lexman is
 * Leroy Merlin's. The item already says its brand — the extractor reads it off
 * the box — so this is the one fact missing between "Anslut GU10 bulb" in the
 * drawer and which shop to go to for another.
 *
 * <p>Kept by hand rather than shipped as a table: which brands matter, and what
 * this household calls the shop ("Leroy"), are the owner's to say, and a
 * built-in list would be stale within the year.
 */
public record BrandStore(String brand, String store) {
    public BrandStore {
        brand = brand == null ? null : brand.trim();
        store = store == null ? null : store.trim();
        if (brand == null || brand.isEmpty()) throw new IllegalArgumentException("Say which brand");
        if (store == null || store.isEmpty()) throw new IllegalArgumentException("Say which store sells " + brand);
        if (brand.length() > 80 || store.length() > 80) throw new IllegalArgumentException("Too long");
    }
}
