package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Project;
import family.eilertsen.rack.domain.model.ProjectId;
import family.eilertsen.rack.domain.model.ProjectNote;
import family.eilertsen.rack.domain.model.ProjectPart;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Takes what a project consumed back out of the drawers.
 *
 * <p><strong>This is the hole in every other guard the app has.</strong> Drift is
 * the failure the whole design is against, and the mitigations so far all watch
 * the same direction — a photo puts things in, a resync corrects what a camera can
 * see. But stock mostly leaves the rack by being <em>used</em>, and a camera was
 * never going to catch that: eight of ten emitter resistors go into an amplifier
 * and the drawer still says ten, correctly recorded, verified last week, wrong.
 * A project is the only thing that knows, so this is where settling up belongs.
 *
 * <p><strong>Previewed, then applied</strong>, the way {@link ResyncSlot} is,
 * because it takes things away. {@link #preview} reads and computes and writes
 * nothing; {@link #settle} is the only half that touches the index.
 *
 * <p><strong>It does not stamp {@code lastVerified}.</strong> That date means
 * somebody looked, and this is arithmetic — ten minus eight, from a quantity that
 * was an estimate off a photograph to begin with. Writing today's date on a
 * deduction would turn the app's one honest signal about staleness into a
 * decoration, and it is the drawers you have not looked at that most need to say
 * so.
 *
 * <p>A row that reaches zero is left at zero rather than deleted. The quantity
 * was an estimate, so "none left" is a thing to go and check, not a thing to act
 * on — and removing the row would take its photographs with it.
 */
@Service
public class SettleProject {

    private final Projects projects;
    private final PartIndex index;

    public SettleProject(Projects projects, PartIndex index) {
        this.projects = projects;
        this.index = index;
    }

    /** What settling up would do, computed against the index as it stands. */
    public Settlement preview(ProjectId id) {
        Project project = require(id);
        List<Change> changes = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (ProjectPart part : project.parts()) {
            if (!ProjectPart.USED.equals(part.status())) continue;
            int used = part.usedQty() == null ? 0 : part.usedQty();
            if (used <= 0) continue;
            if (part.from().isEmpty()) {
                // Bought for the job and never filed, so there is no drawer to take
                // it out of. Nothing to settle and nothing wrong.
                continue;
            }
            // Everything used of one line comes off the first drawer it names. A
            // line spread across two drawers is a judgement about which to draw
            // down, and guessing it would be worse than saying so.
            if (part.from().size() > 1) {
                problems.add(part.part() + " is recorded in " + part.from().size()
                    + " places; taking all " + used + " off " + where(part.from().get(0)) + ".");
            }
            ProjectPart.ProjectSource source = part.from().get(0);
            Located located = locate(source);
            if (located == null) {
                problems.add("Cannot find \"" + source.item() + "\" in " + where(source) + " any more — skipped.");
                continue;
            }
            Integer before = located.item().qtyEstimate();
            if (before == null) {
                problems.add(source.item() + " in " + where(source) + " has no recorded count — skipped.");
                continue;
            }
            int after = Math.max(0, before - used);
            changes.add(new Change(source.container().value(), source.slot().value(),
                located.index(), located.item().name() != null ? located.item().name() : located.item().description(),
                before, used, after));
        }
        return new Settlement(project.id().value(), List.copyOf(changes), List.copyOf(problems));
    }

    /**
     * Applies the settlement and closes the project.
     *
     * <p>Recomputed here rather than taken from the caller: the preview may be
     * minutes old and the drawers are shared with every other page.
     */
    public Result settle(ProjectId id, boolean finish) {
        Project project = require(id);
        Settlement settlement = preview(id);

        Map<Key, List<Change>> perSlot = new LinkedHashMap<>();
        for (Change c : settlement.changes()) {
            perSlot.computeIfAbsent(new Key(c.container(), c.slot()), k -> new ArrayList<>()).add(c);
        }

        int touched = 0;
        for (Map.Entry<Key, List<Change>> e : perSlot.entrySet()) {
            ContainerId container = new ContainerId(e.getKey().container());
            SlotId slotId = new SlotId(e.getKey().slot());
            Slot slot = index.get(container, slotId).orElse(null);
            if (slot == null) continue;

            List<Item> items = new ArrayList<>(slot.items());
            for (Change c : e.getValue()) {
                if (c.index() < 0 || c.index() >= items.size()) continue;
                Item was = items.get(c.index());
                items.set(c.index(), new Item(was.name(), was.description(), was.partNumber(),
                    was.category(), c.after(), was.confidence(), was.tags(), was.qa(),
                    was.sourcePhoto(), was.seenIn()));
                touched++;
            }
            // lastVerified deliberately carried across untouched — see the class note.
            index.save(container, new Slot(slot.id(), List.copyOf(items),
                slot.lastVerified(), slot.printedAt()));
        }

        List<ProjectNote> log = new ArrayList<>(project.log());
        log.add(ProjectNote.app(touched == 0
            ? "Settled up: nothing to take out of stock."
            : "Settled up: " + touched + (touched == 1 ? " drawer row" : " drawer rows") + " reduced."));
        Instant now = Instant.now();
        String status = finish ? Project.DONE : project.status();
        if (finish && !Project.DONE.equals(project.status())) log.add(ProjectNote.app("Finished."));

        Project updated = new Project(project.id(), project.name(), project.brief(), status,
            project.startedAt(), now, finish ? now : project.finishedAt(),
            project.parts(), project.steps(), project.cautions(), List.copyOf(log));
        projects.save(updated);
        return new Result(updated, settlement);
    }

    private Located locate(ProjectPart.ProjectSource source) {
        Slot slot = index.get(source.container(), source.slot()).orElse(null);
        if (slot == null || slot.items() == null) return null;
        String wanted = normalise(source.item());
        for (int i = 0; i < slot.items().size(); i++) {
            Item item = slot.items().get(i);
            String name = item.name() != null && !item.name().isBlank() ? item.name() : item.description();
            if (normalise(name).equals(wanted)) return new Located(i, item);
        }
        return null;
    }

    private Project require(ProjectId id) {
        return projects.get(id).orElseThrow(() ->
            new NoSuchElementException("No such project: " + id.value()));
    }

    private static String where(ProjectPart.ProjectSource s) {
        return s.container().value() + "/" + s.slot().value();
    }

    private static String normalise(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record Located(int index, Item item) {}

    private record Key(String container, String slot) {}

    public record Change(String container, String slot, int index, String item,
                         int before, int used, int after) {}

    /** {@code problems} is what settling cannot do, said out loud rather than skipped quietly. */
    public record Settlement(String project, List<Change> changes, List<String> problems) {}

    public record Result(Project project, Settlement settled) {}
}
