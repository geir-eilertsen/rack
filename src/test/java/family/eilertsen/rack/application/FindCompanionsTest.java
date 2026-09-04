package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Claim;
import family.eilertsen.rack.domain.model.Companion;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.PairFinder;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model call itself is not tested here — what matters is the seam either
 * side of it: that it is shown every other entry with a reference, and that
 * nothing it cites is believed until the index agrees.
 */
class FindCompanionsTest {

    private static final ContainerId LAB = new ContainerId("lab");
    private static final ContainerId CELLAR = new ContainerId("cellar");

    private FakeIndex index;
    private FakeFinder finder;
    private FindCompanions companions;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        finder = new FakeFinder();
        ContainerRegistry registry = new ContainerRegistry(new FakeStore(List.of(
            new Container(LAB, "Lab", ContainerLayout.linear(12, null), 1.0f, "shelf", null, null),
            new Container(CELLAR, "Cellar", ContainerLayout.linear(4, null), 1.0f, "box", null, null))));
        companions = new FindCompanions(index, registry, finder);
    }

    @Test
    void aChargerIsToldWhichDeviceItBelongsWithAndWhereThatIs() {
        Item pi = item("Raspberry Pi 4", "single-board computer, USB-C power");
        index.put(LAB, slot("11", pi));
        index.put(CELLAR, slot("3", item("Paper towels", "kitchen roll")));
        finder.cites(new Companion(0, "lab/11#0", "powers it over USB-C"));

        FindCompanions.Result result = companions.execute(item("USB-C power supply", "5.1V 3A"), null, null);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).container()).isEqualTo(LAB);
        assertThat(result.hits().get(0).slot()).isEqualTo(new SlotId("11"));
        assertThat(result.hits().get(0).item().name()).isEqualTo("Raspberry Pi 4");
        assertThat(result.hits().get(0).why()).isEqualTo("powers it over USB-C");
    }

    @Test
    void theModelIsShownEveryOtherEntryWithItsReferenceAndNotTheSubject() {
        Item phone = item("Phone", "old Android");
        Item charger = item("Phone charger", "micro-USB");
        index.put(LAB, slot("1", phone, charger));
        index.put(CELLAR, slot("2", item("Screws", "M3")));

        companions.execute(charger, LAB, new SlotId("1"));

        assertThat(finder.subjects).hasSize(1);
        assertThat(finder.subjects.get(0)).startsWith("Phone charger | micro-USB");
        // Containers are listed by name, so the cellar comes before the lab.
        assertThat(finder.listing).hasSize(2);
        assertThat(finder.listing.get(0)).startsWith("cellar/2#0 | Screws | M3");
        assertThat(finder.listing.get(1)).startsWith("lab/1#0 | Phone | old Android");
    }

    @Test
    void aReferenceTheListingDoesNotHaveIsNotBelieved() {
        // The model is not permitted to furnish this rack from memory.
        index.put(LAB, slot("1", item("Phone", "old Android")));
        finder.cites(new Companion(0, "lab/9#0", "charges it"), new Companion(0, "nonsense", ""), new Companion(0, "lab/1#0", "charges it"));

        FindCompanions.Result result = companions.execute(item("Phone charger", "micro-USB"), null, null);

        assertThat(result.hits()).extracting(f -> f.item().name()).containsExactly("Phone");
    }

    @Test
    void whatIsAlreadyInTheSameSlotIsReportedAsTogetherRatherThanAsAMove() {
        // The Lumix camera sits beside its charger. Saying it belongs with
        // nothing elsewhere would be true and hide the pair that was found.
        Item camera = item("Panasonic Lumix DMC-FS11", "compact camera");
        Item charger = item("Panasonic Lumix battery charger", "for DMW-BCF10 battery");
        index.put(LAB, slot("11", camera, charger));
        index.put(CELLAR, slot("1", item("Spare DMW-BCF10 battery", "camera battery")));
        finder.cites(new Companion(0, "lab/11#0", "charges its battery"), new Companion(0, "cellar/1#0", "the battery it charges"));

        FindCompanions.Result result = companions.execute(charger, LAB, new SlotId("11"));

        assertThat(result.together()).extracting(f -> f.item().name()).containsExactly("Panasonic Lumix DMC-FS11");
        assertThat(result.hits()).extracting(f -> f.item().name()).containsExactly("Spare DMW-BCF10 battery");
    }

    @Test
    void aGeneralPurposeChargerBelongsWithEverythingItServes() {
        index.put(LAB, slot("1", item("Pixel 7", "phone, USB-C"), item("Kindle", "e-reader, USB-C")));
        index.put(CELLAR, slot("2", item("Steam Deck", "USB-C PD")));
        finder.cites(new Companion(0, "lab/1#0", "charges it"), new Companion(0, "lab/1#1", "charges it"), new Companion(0, "cellar/2#0", "charges it at 45W"));

        FindCompanions.Result result = companions.execute(item("65W USB-C charger", "GaN, PD"), null, null);

        assertThat(result.hits()).extracting(f -> f.item().name()).containsExactly("Pixel 7", "Kindle", "Steam Deck");
    }

    @Test
    void aCitationSurvivesTheItemMovingBecauseItIsRememberedByName() {
        // The answer is cached per subject; the drawer is looked up each time.
        Item pi = item("Raspberry Pi 4", "single-board computer");
        index.put(LAB, slot("11", pi));
        finder.cites(new Companion(0, "lab/11#0", "powers it"));
        Item psu = item("USB-C power supply", "5.1V 3A");

        companions.execute(psu, null, null);
        index.put(LAB, slot("11"));
        index.put(CELLAR, slot("3", pi));
        FindCompanions.Result again = companions.execute(psu, null, null);

        assertThat(finder.calls).isEqualTo(1);
        assertThat(again.hits()).hasSize(1);
        assertThat(again.hits().get(0).container()).isEqualTo(CELLAR);
    }

    @Test
    void everyProposedPairIsPutToTheModelAgainOnItsOwnAndOnlyWhatStandsIsKept() {
        // Picked out of the whole listing, the spring "for Elco switch" was the
        // pair of an ELKO USB outlet — a brand in common. Judged beside it
        // alone, it is not.
        Item outlet = item("ELKO dual USB wall outlet", "flush-mount, two USB-A ports");
        index.put(LAB, slot("1", outlet));
        index.put(CELLAR, slot("2", item("small coil spring for Elco switch", "compression spring"), item("5V 2A USB charger", "wall wart")));
        finder.cites(new Companion(0, "cellar/2#0", "spring for the switch inside"), new Companion(0, "cellar/2#1", "plugs into its USB port"));
        finder.rejects("small coil spring for Elco switch");

        FindCompanions.Result result = companions.execute(outlet, LAB, new SlotId("1"));

        assertThat(finder.claims).hasSize(2);
        assertThat(finder.claims.get(0).subject()).startsWith("ELKO dual USB wall outlet |");
        assertThat(finder.claims.get(0).candidate()).startsWith("small coil spring for Elco switch |");
        assertThat(finder.claims.get(0).why()).isEqualTo("spring for the switch inside");
        assertThat(result.hits()).extracting(f -> f.item().name()).containsExactly("5V 2A USB charger");
    }

    @Test
    void aFiledBatchIsOneCallWithEachItemAnsweredForItself() {
        // Eight items in one shot used to be eight calls in a row, and the
        // listing is most of each prompt.
        index.put(LAB, slot("11", item("Raspberry Pi 4", "single-board computer")));
        index.put(CELLAR, slot("3", item("Panasonic Lumix DMC-FS11", "compact camera")));
        finder.cites(new Companion(1, "cellar/3#0", "charges its battery"), new Companion(0, "lab/11#0", "powers it"));

        List<FindCompanions.Result> results = companions.executeAll(List.of(
            item("USB-C power supply", "5.1V 3A"),
            item("Lumix battery charger", "DE-A60"),
            new Item(null, "unreadable", null, "other", 1, 0.3, List.of(), List.of(), null, null, List.of())));

        assertThat(finder.calls).isEqualTo(1);
        assertThat(finder.subjects).hasSize(2);
        assertThat(results).hasSize(3);
        assertThat(results.get(0).hits()).extracting(f -> f.item().name()).containsExactly("Raspberry Pi 4");
        assertThat(results.get(1).hits()).extracting(f -> f.item().name()).containsExactly("Panasonic Lumix DMC-FS11");
        assertThat(results.get(2).hits()).isEmpty();
    }

    @Test
    void anItemWithNoNameAsksNothing() {
        index.put(LAB, slot("1", item("Phone", "old Android")));

        FindCompanions.Result result = companions.execute(new Item(null, "some cable", null, "other", 1, 0.9, List.of(), List.of(), null, null, List.of()), null, null);

        assertThat(result.hits()).isEmpty();
        assertThat(finder.calls).isZero();
    }

    private static Item item(String name, String description) {
        return new Item(name, description, null, "electronics", 1, 0.9, List.of(), List.of(), null, null, List.of());
    }

    private static Slot slot(String id, Item... items) {
        return new Slot(new SlotId(id), List.of(items), null, null);
    }

    private static final class FakeFinder implements PairFinder {
        int calls;
        List<String> subjects;
        List<String> listing;
        private List<Companion> answer = List.of();

        void cites(Companion... companions) {
            this.answer = List.of(companions);
        }

        @Override
        public List<Companion> find(List<String> subjects, List<String> listing) {
            calls++;
            this.subjects = subjects;
            this.listing = listing;
            return answer;
        }

        List<Claim> claims;
        private Set<String> rejected = Set.of();

        void rejects(String... candidateNames) {
            this.rejected = Set.of(candidateNames);
        }

        @Override
        public List<Boolean> confirm(List<Claim> claims) {
            this.claims = claims;
            List<Boolean> stands = new ArrayList<>();
            for (Claim c : claims) stands.add(rejected.stream().noneMatch(name -> c.candidate().startsWith(name + " |")));
            return stands;
        }
    }

    private record FakeStore(List<Container> containers) implements ContainerStore {
        @Override
        public List<Container> loadAll() {
            return containers;
        }

        @Override
        public void saveAll(List<Container> containers) {
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        void put(ContainerId container, Slot slot) {
            slots.computeIfAbsent(container, k -> new LinkedHashMap<>()).put(slot.id(), slot);
        }

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.ofNullable(slots.getOrDefault(container, Map.of()).get(slot));
        }

        @Override
        public void save(ContainerId container, Slot slot) {
            put(container, slot);
        }

        @Override
        public Collection<Slot> all(ContainerId container) {
            return new ArrayList<>(slots.getOrDefault(container, Map.of()).values());
        }

        @Override
        public void forget(ContainerId container) {
            slots.remove(container);
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return List.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }

        @Override
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public Set<String> documentsInUse() {
            return Set.of();
        }
    }
}
