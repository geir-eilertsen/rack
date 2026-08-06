package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 * side of it: that the whole index goes in, and that nothing the model says
 * about the contents of a drawer is believed without the index agreeing.
 */
class AskAboutRackTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final ContainerId LAB = new ContainerId("lab");

    private FakeIndex index;
    private ContainerRegistry registry;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        registry = new ContainerRegistry(new FakeStore(List.of(
            new Container(RACK, "Skuffereol", ContainerLayout.grid(2, 2), 1.0f, "drawer"),
            new Container(LAB, "Lab", ContainerLayout.linear(3, null), 1.0f, "shelf"))));
    }

    @Test
    void everyItemInEveryContainerGoesIntoTheQuestion() {
        // No sampling and no top-k: "what am I missing" is answerable only from
        // the whole list, because an item left out is reported as one to go and buy.
        index.put(RACK, slot("A1", item("220uF 100V capacitor", "radial electrolytic", 4)));
        index.put(RACK, slot("A2", item("BC547 transistor", "TO-92", 30)));
        index.put(LAB, slot("1", item("Solder wick", "Chemtronics braid", 1),
            item("Heat sink compound", "Dow Corning 340", 1)));

        AskAboutRack.Inventory inventory = AskAboutRack.inventory(registry, index);

        assertThat(inventory.lines()).hasSize(4);
        assertThat(inventory.lines().get(0))
            .startsWith("rack/A1 | 220uF 100V capacitor | radial electrolytic")
            .contains(" | 4 | ");
        // Both containers, not just the first one asked about.
        assertThat(inventory.lines()).anyMatch(l -> l.startsWith("lab/1 | Heat sink compound"));
    }

    @Test
    void eachLineSaysHowLongAgoTheDrawerWasChecked() {
        // Drift is the failure this app exists to prevent, so an answer that
        // leans on a year-old reading has to be able to say so.
        index.put(RACK, new Slot(new SlotId("A1"), List.of(item("Ear tips", "silicone", 6)),
            Instant.now().minusSeconds(60L * 60 * 24 * 30), null));
        index.put(LAB, new Slot(new SlotId("1"), List.of(item("Floppy drive", "Sony", 1)), null, null));

        List<String> lines = AskAboutRack.inventory(registry, index).lines();

        assertThat(lines).anyMatch(l -> l.contains("last checked: 30 days ago"));
        assertThat(lines).anyMatch(l -> l.contains("last checked: never"));
    }

    @Test
    void keepsAClaimTheIndexAgreesWith() {
        index.put(RACK, slot("A1", item("220uF 100V capacitor", "radial", 4)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<AskAboutRack.Need> checked = AskAboutRack.verify(List.of(
            need("Reservoir capacitor", "have",
                new AskAboutRack.Found("rack", "A1", "220uF 100V capacitor", null))), held);

        assertThat(checked).hasSize(1);
        assertThat(checked.get(0).status()).isEqualTo("have");
        assertThat(checked.get(0).found()).hasSize(1);
    }

    @Test
    void aClaimNamingAnItemTheDrawerDoesNotHoldIsDropped() {
        // The one failure this cannot be allowed: telling someone they own a part
        // they do not. They would walk to the drawer, and either the drawer or the
        // app would be wrong. The model is not permitted to furnish this rack
        // from memory, however plausible the part sounds in a Quad 606.
        index.put(RACK, slot("A1", item("220uF 100V capacitor", "radial", 4)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<AskAboutRack.Need> checked = AskAboutRack.verify(List.of(
            need("Bias trimmer", "have",
                new AskAboutRack.Found("rack", "A1", "Bourns 470R trimmer", null))), held);

        assertThat(checked.get(0).found()).isEmpty();
        // And a "have" with nothing left to stand on is a miss, not a have.
        assertThat(checked.get(0).status()).isEqualTo("missing");
    }

    @Test
    void aClaimAgainstADrawerThatDoesNotExistIsDropped() {
        index.put(RACK, slot("A1", item("220uF 100V capacitor", "radial", 4)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<AskAboutRack.Need> checked = AskAboutRack.verify(List.of(
            need("Reservoir capacitor", "have",
                new AskAboutRack.Found("rack", "E9", "220uF 100V capacitor", null))), held);

        assertThat(checked.get(0).found()).isEmpty();
        assertThat(checked.get(0).status()).isEqualTo("missing");
    }

    @Test
    void aRealClaimSurvivesBeingQuotedBackInADifferentCase() {
        // The model is echoing a label it was handed, not a key, so it comes back
        // with whatever capitalisation and spacing it saw fit.
        index.put(RACK, slot("A1", item("220uF 100V Capacitor", "radial", 4)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<AskAboutRack.Need> checked = AskAboutRack.verify(List.of(
            need("Reservoir capacitor", "have",
                new AskAboutRack.Found("RACK", "a1", "220uf 100v   capacitor", null))), held);

        assertThat(checked.get(0).found()).hasSize(1);
        assertThat(checked.get(0).status()).isEqualTo("have");
    }

    @Test
    void aMissingPartKeepsItsStatusAndNeedsNoCitation() {
        List<AskAboutRack.Need> checked = AskAboutRack.verify(
            List.of(need("Mains transformer", "missing")), Map.of());

        assertThat(checked).hasSize(1);
        assertThat(checked.get(0).status()).isEqualTo("missing");
    }

    @Test
    void keepsOnlyTheHalfOfAPartialClaimThatIsReal() {
        index.put(RACK, slot("A1", item("100K resistors", "1/4W", 20)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<AskAboutRack.Need> checked = AskAboutRack.verify(List.of(
            need("Feedback resistors", "partial",
                new AskAboutRack.Found("rack", "A1", "100K resistors", "close enough"),
                new AskAboutRack.Found("rack", "A1", "82K resistors", null))), held);

        assertThat(checked.get(0).found()).hasSize(1);
        assertThat(checked.get(0).found().get(0).item()).isEqualTo("100K resistors");
        assertThat(checked.get(0).status()).isEqualTo("partial");
    }

    @Test
    void anItemWithNoNameIsStillCitableByItsDescription() {
        // Items catalogued before the name/description split have only the one.
        index.put(RACK, slot("A1", new Item(null, "Solder lugs - small ring terminals", null,
            "connector", 20, 0.8, List.of(), null, List.of(), null, null)));
        Map<String, List<String>> held = AskAboutRack.inventory(registry, index).held();

        List<AskAboutRack.Need> checked = AskAboutRack.verify(List.of(
            need("Solder lugs", "have",
                new AskAboutRack.Found("rack", "A1", "Solder lugs - small ring terminals", null))), held);

        assertThat(checked.get(0).status()).isEqualTo("have");
    }

    @Test
    void takesTheObjectOutOfWhateverTheModelWrappedItIn() {
        // Asked for JSON and nothing else, the first real call to this opened with
        // "I'll go through what a Quad 606 restoration needs" and put the object
        // below it. Tightening the instruction is a guess about the next reply.
        String want = "{\"summary\":\"ok\"}";
        assertThat(AskAboutRack.json(want)).isEqualTo(want);
        assertThat(AskAboutRack.json("```json\n" + want + "\n```")).isEqualTo(want);
        assertThat(AskAboutRack.json("I'll go through what it needs.\n\n" + want)).isEqualTo(want);
        assertThat(AskAboutRack.json(want + "\n\nHope that helps!")).isEqualTo(want);
        assertThat(AskAboutRack.json("Sure:\n```\n" + want + "\n```\nAnything else?")).isEqualTo(want);
    }

    @Test
    void aNestedObjectKeepsItsBracesWhenTheWrapperIsStripped() {
        String want = "{\"summary\":\"ok\",\"checklist\":[{\"part\":\"cap\",\"found\":[{\"slot\":\"A1\"}]}]}";
        assertThat(AskAboutRack.json("Here you go:\n" + want)).isEqualTo(want);
    }

    private static AskAboutRack.Need need(String part, String status, AskAboutRack.Found... found) {
        return new AskAboutRack.Need(part, "for the job", status, List.of(found), null);
    }

    private static Slot slot(String id, Item... items) {
        return new Slot(new SlotId(id), List.of(items), Instant.now(), null);
    }

    private static Item item(String name, String description, int qty) {
        return new Item(name, description, null, "other", qty, 0.9, List.of(), null, List.of(), null, null);
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
        public Set<String> photosInUse() {
            return Set.of();
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return List.of();
        }

        @Override
        public List<SearchHit> searchBySimilarity(float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
