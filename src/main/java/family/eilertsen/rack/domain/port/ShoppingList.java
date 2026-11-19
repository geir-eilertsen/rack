package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.BrandStore;
import family.eilertsen.rack.domain.model.Framing;
import family.eilertsen.rack.domain.model.ShoppingEntry;

import java.util.List;

/**
 * The things to buy, and the pictures that say which one. The list owns its
 * pictures outright — see {@link ShoppingEntry} for why they are not the
 * rack's photographs.
 */
public interface ShoppingList {

    /** In the order they were added. */
    List<ShoppingEntry> all();

    /** Adds or replaces, by id. */
    void save(ShoppingEntry entry);

    void delete(String id);

    /** Keeps a picture for an entry, fitted for a phone; returns the name it is kept under. */
    String keepPhoto(byte[] image, Framing seen);

    byte[] photo(String name);

    /** The same picture, no longer than {@code maxEdge} on its longer side — a phone decodes what it is sent. */
    byte[] thumbnail(String name, int maxEdge);

    /** Deleting one that is already gone is not an error. */
    void dropPhoto(String name);

    /** Which store sells which brand, in the order they were written down. */
    List<BrandStore> brands();

    void saveBrands(List<BrandStore> brands);
}
