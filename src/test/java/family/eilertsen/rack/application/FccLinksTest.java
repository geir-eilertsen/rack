package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Document;
import family.eilertsen.rack.domain.model.FccListing;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.FccRegistry;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FccLinksTest {

    static final ContainerId LAB = new ContainerId("lab");
    static final SlotId TEN = new SlotId("10");
    static final Instant CHECKED = Instant.parse("2026-08-05T11:15:00Z");

    FakeIndex index;
    FakeRegister register;
    FccLinks links;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        register = new FakeRegister();
        register.listed.put("DZLMRCJ134", new FccListing("https://fccid.io/DZLMRCJ134",
            "FCC ID DZLMRCJ134 - 2.4GHz cordless mouse"));
        ContainerRegistry registry = new ContainerRegistry(new ContainerStore() {
            public List<Container> loadAll() {
                return List.of(new Container(LAB, "Lab", ContainerLayout.linear(12, null), 1.0f, "shelf", null, null));
            }
            public void saveAll(List<Container> c) {}
        });
        links = new FccLinks(index, registry, register, true,
            Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void readsTheIdOutOfTheItemsOwnWords() {
        assertThat(FccLinks.fccIdOf(item("Logitech wireless laser mouse with nano receiver; FCC ID DZLMRCJ134, IC 1807B-MRCJ134")))
            .contains("DZLMRCJ134");
        assertThat(FccLinks.fccIdOf(item("Router, FCC-ID: 2AB3C-X1 on the label"))).contains("2AB3C-X1");
        assertThat(FccLinks.fccIdOf(item("fcc id QDS-BRCM1050."))).contains("QDS-BRCM1050");
        // Not an ID: lower-case words, no digit, or no ID at all.
        assertThat(FccLinks.fccIdOf(item("FCC ID label on the underside"))).isEmpty();
        assertThat(FccLinks.fccIdOf(item("FCC compliant, CE marked"))).isEmpty();
        assertThat(FccLinks.fccIdOf(item("FCC ID ABCDEF"))).isEmpty();
    }

    @Test
    void aListedIdBecomesALinkAndTheDrawerIsNotCalledChecked() {
        index.save(LAB, new Slot(TEN, List.of(item("Mouse; FCC ID DZLMRCJ134"), item("Mesh pouch")), CHECKED, null));

        Slot linked = links.link(LAB, TEN).orElseThrow();

        Document doc = linked.items().get(0).documents().get(0);
        assertThat(doc.url()).isEqualTo("https://fccid.io/DZLMRCJ134");
        assertThat(doc.title()).isEqualTo("FCC ID DZLMRCJ134 - 2.4GHz cordless mouse");
        assertThat(linked.items().get(1).documents()).isEmpty();
        assertThat(linked.lastVerified()).isEqualTo(CHECKED);
        // Already linked: nothing more to do, and nothing asked.
        assertThat(links.link(LAB, TEN)).isEmpty();
        assertThat(register.asks).containsExactly("DZLMRCJ134");
    }

    @Test
    void anIdTheRegisterHasNotGotLinksNothing() {
        index.save(LAB, new Slot(TEN, List.of(item("Charger; FCC ID ZZZQQQ999")), CHECKED, null));
        assertThat(links.link(LAB, TEN)).isEmpty();
        assertThat(index.get(LAB, TEN).orElseThrow().items().get(0).documents()).isEmpty();
    }

    @Test
    void theBootPassLeavesAloneWhatWasAskedBefore() {
        // Linked once, then taken off by hand: the boot pass must not put it back.
        index.save(LAB, new Slot(TEN, List.of(item("Mouse; FCC ID DZLMRCJ134")), CHECKED, null));
        register.asked.add("DZLMRCJ134");
        links.sweep();
        assertThat(index.get(LAB, TEN).orElseThrow().items().get(0).documents()).isEmpty();

        // One never asked about is.
        register.asked.clear();
        links.sweep();
        assertThat(index.get(LAB, TEN).orElseThrow().items().get(0).documents()).hasSize(1);
    }

    @Test
    void anUnreachableRegisterIsLeftAloneForAWhile() {
        register.down = true;
        index.save(LAB, new Slot(TEN, List.of(item("Mouse; FCC ID DZLMRCJ134")), CHECKED, null));
        assertThat(links.link(LAB, TEN)).isEmpty();
        assertThat(links.link(LAB, TEN)).isEmpty();
        assertThat(register.asks).hasSize(1);
    }

    @Test
    void offAsksNothing() {
        FccLinks off = new FccLinks(index, null, register, false, Clock.systemUTC());
        index.save(LAB, new Slot(TEN, List.of(item("Mouse; FCC ID DZLMRCJ134")), CHECKED, null));
        assertThat(off.link(LAB, TEN)).isEmpty();
        assertThat(register.asks).isEmpty();
    }

    static Item item(String description) {
        return new Item(null, description, null, null, 1, 0.9, List.of(), null, null, null, List.of());
    }

    static final class FakeRegister implements FccRegistry {
        final Map<String, FccListing> listed = new HashMap<>();
        final Set<String> asked = new java.util.HashSet<>();
        final List<String> asks = new ArrayList<>();
        boolean down;

        @Override
        public Optional<FccListing> lookup(String fccId) {
            asks.add(fccId);
            if (down) throw new IllegalStateException("unreachable");
            asked.add(fccId);
            return Optional.ofNullable(listed.get(fccId));
        }

        @Override
        public boolean asked(String fccId) {
            return asked.contains(fccId);
        }
    }

    static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();
        public Optional<Slot> get(ContainerId c, SlotId s) { return Optional.ofNullable(slots.getOrDefault(c, Map.of()).get(s)); }
        public void save(ContainerId c, Slot s) { slots.computeIfAbsent(c, k -> new LinkedHashMap<>()).put(s.id(), s); }
        public Collection<Slot> all(ContainerId c) { return List.copyOf(slots.getOrDefault(c, Map.of()).values()); }
        public List<SearchHit> searchByKeyword(String q) { return List.of(); }
        public void forget(ContainerId c) { slots.remove(c); }
        public Set<String> documentsInUse() { return Set.of(); }
        public Set<String> photosInUse() { return Set.of(); }
        public Set<String> vocabulary() { return Set.of(); }
    }
}
