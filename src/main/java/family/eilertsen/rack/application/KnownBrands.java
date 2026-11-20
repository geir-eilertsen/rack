package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.BrandStore;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.ShoppingList;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every brand the rack already knows: what items carry and what the
 * brand → store rules name. Offered wherever a brand is typed, so the second
 * Anslut bulb goes in as "Anslut" rather than "anslut" — one letter of
 * difference and a rule written for one spelling has to be matched against
 * the other. The same reasoning as a container's location: a list that
 * builds itself rather than one somebody keeps.
 */
@Service
public class KnownBrands {

    private final ContainerRegistry registry;
    private final PartIndex index;
    private final ShoppingList shopping;

    public KnownBrands(ContainerRegistry registry, PartIndex index, ShoppingList shopping) {
        this.registry = registry;
        this.index = index;
        this.shopping = shopping;
    }

    /**
     * Grouped ignoring case, each under the spelling used most — a rule counts
     * as one use, so a rule typed carefully once does not lose to nothing.
     * Sorted ignoring case.
     */
    public List<String> all() {
        Map<String, Map<String, Integer>> spellings = new HashMap<>();
        for (Container container : registry.all()) {
            for (Slot slot : index.all(container.id())) {
                if (slot.items() == null) continue;
                for (Item item : slot.items()) count(spellings, item.brand());
            }
        }
        for (BrandStore rule : shopping.brands()) count(spellings, rule.brand());

        List<String> brands = new ArrayList<>();
        for (Map<String, Integer> forms : spellings.values()) {
            brands.add(forms.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                    .thenComparing(Map.Entry.comparingByKey())) // ties: "Anslut" before "anslut"
                .orElseThrow().getKey());
        }
        brands.sort(String.CASE_INSENSITIVE_ORDER);
        return brands;
    }

    private static void count(Map<String, Map<String, Integer>> spellings, String brand) {
        if (brand == null || brand.isBlank()) return;
        String b = brand.strip();
        spellings.computeIfAbsent(b.toLowerCase(Locale.ROOT), k -> new HashMap<>()).merge(b, 1, Integer::sum);
    }
}
