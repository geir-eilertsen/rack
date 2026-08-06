package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.model.ProjectPart;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import family.eilertsen.rack.domain.port.ProjectStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectLifecycleTest {

    private static final ContainerId RACK = new ContainerId("rack");
    private static final SlotId B7 = new SlotId("B7");

    private FakeIndex index;
    private Projects projects;
    private StartProject start;
    private RunProject run;
    private SettleProject settle;

    @BeforeEach
    void setUp() {
        index = new FakeIndex();
        projects = new Projects(new FakeStore());
        projects.load();
        start = new StartProject(projects);
        run = new RunProject(projects);
        settle = new SettleProject(projects, index);
    }

    @Test
    void aProjectTakesItsNameFromTheFirstSentenceAndKeepsTheWholeBrief() {
        // The brief is a fine sentence and a poor heading, so the two differ.
        Project p = start.execute(new StartProject.Request(null,
            "I am restoring a Quad 606 amplifier. Do I have all the parts?",
            List.of(), List.of(), List.of()));

        assertThat(p.name()).isEqualTo("I am restoring a Quad 606 amplifier");
        assertThat(p.brief()).isEqualTo("I am restoring a Quad 606 amplifier. Do I have all the parts?");
        assertThat(p.id().value()).isEqualTo("i-am-restoring-a-quad-606-amplifier");
    }

    @Test
    void aSecondProjectOfTheSameNameGetsItsOwnId() {
        start.execute(new StartProject.Request("Quad 606", null, List.of(), List.of(), List.of()));
        Project again = start.execute(new StartProject.Request("Quad 606", null, List.of(), List.of(), List.of()));

        assertThat(again.id().value()).isEqualTo("quad-606-2");
    }

    @Test
    void whereAProjectStartsIsAFactAboutItsParts() {
        Project shopping = start.execute(new StartProject.Request("Recap", null,
            List.of(line("10000uF 80V", ProjectPart.TO_BUY)), List.of(step("Desolder")), List.of()));
        assertThat(shopping.status()).isEqualTo(Project.SHOPPING);

        Project building = start.execute(new StartProject.Request("Rewire", null,
            List.of(line("Cable", ProjectPart.IN_STOCK)), List.of(step("Strip the ends")), List.of()));
        assertThat(building.status()).isEqualTo(Project.BUILDING);

        Project planning = start.execute(new StartProject.Request("Idea", null,
            List.of(), List.of(), List.of()));
        assertThat(planning.status()).isEqualTo(Project.PLANNING);
    }

    @Test
    void theLastPartArrivingMovesTheProjectOnToTheBuild() {
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(line("Caps", ProjectPart.TO_BUY), line("Transistors", ProjectPart.TO_BUY)),
            List.of(step("Desolder")), List.of()));

        p = run.setPartStatus(p.id(), 0, ProjectPart.ARRIVED, null);
        assertThat(p.status()).isEqualTo(Project.SHOPPING);

        p = run.setPartStatus(p.id(), 1, ProjectPart.ARRIVED, null);
        assertThat(p.status()).isEqualTo(Project.BUILDING);
        assertThat(p.log()).last().extracting(n -> n.text())
            .isEqualTo("Everything is here — on to the build.");
    }

    @Test
    void tickingAStepDoesNotEndTheShopping() {
        // The first steps of a job are reading the manual and photographing the
        // inside, both of which you do while waiting for the post. A real run
        // ticked step one with nineteen parts unordered and the project promptly
        // called itself "building".
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(line("Caps", ProjectPart.TO_BUY)),
            List.of(step("Download the service manual")), List.of()));
        assertThat(p.status()).isEqualTo(Project.SHOPPING);

        p = run.tickStep(p.id(), 0, true);

        assertThat(p.stepsDone()).isEqualTo(1);
        assertThat(p.status()).isEqualTo(Project.SHOPPING);
    }

    @Test
    void tickingTheLastStepDoesNotFinishTheProject() {
        // Putting the lid back on and knowing it works are different things, and
        // only the person holding the amplifier can say the second.
        Project p = start.execute(new StartProject.Request("Recap", null, List.of(),
            List.of(step("Desolder"), step("Resolder")), List.of()));

        p = run.tickStep(p.id(), 0, true);
        p = run.tickStep(p.id(), 1, true);

        assertThat(p.stepsDone()).isEqualTo(2);
        // Nothing to buy, so it started in the build and stays there until told.
        assertThat(p.status()).isEqualTo(Project.BUILDING);
        assertThat(p.next()).isNull();
    }

    @Test
    void everyChangeLandsInTheLog() {
        // Six weeks later "when did the transistors arrive" is the question, and
        // the current state cannot answer it.
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(line("Caps", ProjectPart.TO_BUY)), List.of(step("Desolder")), List.of()));

        p = run.setPartStatus(p.id(), 0, ProjectPart.ARRIVED, null);
        p = run.tickStep(p.id(), 0, true);
        p = run.addNote(p.id(), "one pad lifted on the left board");

        assertThat(p.log()).extracting(n -> n.text())
            .contains("Project started — 1 parts, 1 steps.", "Caps → arrived",
                "Done: Desolder", "one pad lifted on the left board");
        assertThat(p.log()).last().extracting(n -> n.by()).isEqualTo("user");
    }

    @Test
    void reopeningAFinishedProjectClearsItsFinishDate() {
        Project p = start.execute(new StartProject.Request("Recap", null, List.of(), List.of(), List.of()));

        p = run.setStatus(p.id(), Project.DONE);
        assertThat(p.finishedAt()).isNotNull();

        p = run.setStatus(p.id(), Project.BUILDING);
        assertThat(p.finishedAt()).isNull();
    }

    @Test
    void settlingUpTakesWhatWasUsedOutOfTheDrawer() {
        // The failure nothing else in the app could catch: eight of ten resistors
        // go into an amplifier and the drawer still says ten.
        index.put(RACK, B7, item("0.22R 5W resistors", 10));
        Project p = withUsedPart("0.22R 5W resistors", 8);

        SettleProject.Settlement pre = settle.preview(p.id());
        assertThat(pre.changes()).singleElement().satisfies(c -> {
            assertThat(c.before()).isEqualTo(10);
            assertThat(c.used()).isEqualTo(8);
            assertThat(c.after()).isEqualTo(2);
        });
        // The preview wrote nothing.
        assertThat(qty()).isEqualTo(10);

        settle.settle(p.id(), true);

        assertThat(qty()).isEqualTo(2);
        assertThat(projects.get(p.id()).orElseThrow().status()).isEqualTo(Project.DONE);
    }

    @Test
    void settlingUpDoesNotStampTheDrawerAsVerified() {
        // Ten minus eight is arithmetic, not a look in the drawer. Writing today's
        // date on a deduction would turn the app's one honest staleness signal
        // into a decoration.
        Instant longAgo = Instant.parse("2026-01-01T00:00:00Z");
        index.put(RACK, new Slot(B7, List.of(item("0.22R 5W resistors", 10)), longAgo, null));
        Project p = withUsedPart("0.22R 5W resistors", 8);

        settle.settle(p.id(), true);

        assertThat(index.get(RACK, B7).orElseThrow().lastVerified()).isEqualTo(longAgo);
    }

    @Test
    void aRowUsedDownToNothingStaysAtZeroRatherThanVanishing() {
        // The count was an estimate off a photograph, so "none left" is something
        // to go and check. Removing the row would take its photographs too.
        index.put(RACK, B7, item("Solder lugs", 4));
        Project p = withUsedPart("Solder lugs", 9);

        settle.settle(p.id(), true);

        Slot after = index.get(RACK, B7).orElseThrow();
        assertThat(after.items()).hasSize(1);
        assertThat(after.items().get(0).qtyEstimate()).isZero();
    }

    @Test
    void saysSoWhenAPartCannotBeSettled() {
        // Skipping quietly is how an index starts lying.
        index.put(RACK, B7, item("Something else", 5));
        Project p = withUsedPart("0.22R 5W resistors", 8);

        SettleProject.Settlement pre = settle.preview(p.id());

        assertThat(pre.changes()).isEmpty();
        assertThat(pre.problems()).singleElement().asString()
            .contains("0.22R 5W resistors").contains("rack/B7");
    }

    @Test
    void aPartWithNoRecordedCountIsLeftAloneAndReported() {
        index.put(RACK, B7, new Item("Heat shrink", "assorted", null, "other", null, 0.9,
            List.of(), List.of(), null, null));
        Project p = withUsedPart("Heat shrink", 2);

        SettleProject.Settlement pre = settle.preview(p.id());

        assertThat(pre.changes()).isEmpty();
        assertThat(pre.problems()).singleElement().asString().contains("no recorded count");
    }

    @Test
    void aPartBoughtForTheJobAndNeverFiledSettlesToNothingQuietly() {
        // There is no drawer to take it out of, and that is not a problem.
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(line("10000uF 80V", ProjectPart.TO_BUY)), List.of(), List.of()));
        p = run.setPartStatus(p.id(), 0, ProjectPart.USED, 2);

        SettleProject.Settlement pre = settle.preview(p.id());

        assertThat(pre.changes()).isEmpty();
        assertThat(pre.problems()).isEmpty();
    }

    @Test
    void onlyPartsMarkedUsedAreSettled() {
        index.put(RACK, B7, item("0.22R 5W resistors", 10));
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(new StartProject.Line("0.22R 5W resistors", "8", ProjectPart.ARRIVED, null, null, null, null,
                List.of(new StartProject.Source("rack", "B7", "0.22R 5W resistors")))),
            List.of(), List.of()));

        assertThat(settle.preview(p.id()).changes()).isEmpty();
        settle.settle(p.id(), false);
        assertThat(qty()).isEqualTo(10);
    }

    @Test
    void refusesAnUnknownStatusRatherThanStoringIt() {
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(line("Caps", ProjectPart.TO_BUY)), List.of(), List.of()));

        assertThatThrownBy(() -> run.setStatus(p.id(), "halfway"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("halfway");
        assertThatThrownBy(() -> run.setPartStatus(p.id(), 0, "maybe", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maybe");
    }

    private Project withUsedPart(String itemName, int used) {
        Project p = start.execute(new StartProject.Request("Recap", null,
            List.of(new StartProject.Line(itemName, String.valueOf(used), ProjectPart.IN_STOCK,
                null, null, null, null,
                List.of(new StartProject.Source("rack", "B7", itemName)))),
            List.of(), List.of()));
        return run.setPartStatus(p.id(), 0, ProjectPart.USED, used);
    }

    private int qty() {
        return index.get(RACK, B7).orElseThrow().items().get(0).qtyEstimate();
    }

    private static StartProject.Line line(String part, String status) {
        return new StartProject.Line(part, "1", status, null, null, null, null, List.of());
    }

    private static StartProject.Step step(String title) {
        return new StartProject.Step(title, "do it", List.of());
    }

    private static Item item(String name, Integer qty) {
        return new Item(name, name, null, "other", qty, 0.9, List.of(), List.of(), null, null);
    }

    private static final class FakeStore implements ProjectStore {
        private final Map<ProjectId, Project> saved = new LinkedHashMap<>();

        @Override
        public List<Project> loadAll() {
            return List.copyOf(saved.values());
        }

        @Override
        public void save(Project project) {
            saved.put(project.id(), project);
        }

        @Override
        public void delete(ProjectId id) {
            saved.remove(id);
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        void put(ContainerId container, SlotId slot, Item... items) {
            put(container, new Slot(slot, List.of(items), Instant.now(), null));
        }

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
        public Set<String> vocabulary() {
            return Set.of();
        }
    }
}
