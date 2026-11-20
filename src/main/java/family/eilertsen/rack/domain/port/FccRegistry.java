package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.FccListing;

import java.util.Optional;

/**
 * The FCC's register of radio equipment, by FCC ID. A device that transmits
 * — a wireless mouse, a router, a phone charger with Qi in it — carries one
 * on its label, and the register's page for it holds the photographs, the
 * manual and the internal pictures the maker filed.
 */
public interface FccRegistry {

    /**
     * The register's page for {@code fccId}, or empty when it has none.
     * Throws when the register could not be asked — that is "unknown", not
     * "not listed", and must not be remembered as either.
     */
    Optional<FccListing> lookup(String fccId);

    /** Whether {@code fccId} has been looked up before and the answer kept, either way. */
    boolean asked(String fccId);
}
