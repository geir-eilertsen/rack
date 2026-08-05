package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartExtractor;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Re-photographs a slot to answer "this is what is in here <em>now</em>".
 *
 * <p>{@link AddPhotoToSlot} appends, which is right when you are putting
 * something in a drawer and wrong when you are checking one: shoot a drawer you
 * already filed and you get two of everything. Replacing the slot outright
 * would be worse than the drift it fixes, though — a corrected part number, a
 * hand-written name, the answers stored under Ask AI, the frames an item was
 * seen in are all work no camera can redo.
 *
 * <p>So a resync is a diff, in two halves. {@link #preview} extracts and lines
 * the new reading up against what is recorded, writing nothing; {@link #apply}
 * takes back the decisions and is the only half that touches disk. What the
 * user confirms is a removal, so it is worth a round trip.
 */
@Service
public class ResyncSlot {

    /**
     * How much of the shorter label's wording two readings must share to be the
     * same thing. Both sides came from the same model reading the same object on
     * different days, so most words survive ("bag of M4 bolts" against "M4 hex
     * bolts"); measuring against the <em>shorter</em> side is what stops a long
     * description from diluting a four-word name into a miss.
     */
    private static final double SAME_ITEM = 0.5;

    /** A part number is an identity, not a resemblance, so it outranks any wording overlap. */
    private static final double BY_PART_NUMBER = 2.0;

    private final ImageStore images;
    private final PartExtractor extractor;
    private final PartIndex index;

    public ResyncSlot(ImageStore images, PartExtractor extractor, PartIndex index) {
        this.images = images;
        this.extractor = extractor;
        this.index = index;
    }

    /**
     * Reads the batch and lines it up against the slot as recorded. Nothing is
     * stored — not the photos either, because a preview the user abandons must
     * not leave frames behind for the real run to have to clean up.
     */
    public Preview preview(ContainerId container, SlotId slot, List<byte[]> photos) {
        requireBatch(photos);

        List<Item> current = currentItems(container, slot);
        List<Extraction> extractions = extractor.extract(photos);
        int[] pairedWith = pair(current, extractions);

        List<Matched> matched = new ArrayList<>();
        List<Gone> gone = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            Item held = current.get(i);
            if (pairedWith[i] < 0) {
                gone.add(new Gone(i, held));
                continue;
            }
            Extraction found = extractions.get(pairedWith[i]);
            matched.add(new Matched(i, held, found.item(), qty(held), qty(found.item()),
                framesOf(found, photos.size())));
        }

        List<Added> added = new ArrayList<>();
        for (int j = 0; j < extractions.size(); j++) {
            if (contains(pairedWith, j)) continue;
            added.add(new Added(extractions.get(j).item(), framesOf(extractions.get(j), photos.size())));
        }

        return new Preview(List.copyOf(matched), List.copyOf(gone), List.copyOf(added));
    }

    /**
     * Writes the decided state: the kept items in the order given, then the new
     * ones. An item the decisions do not name is simply not in the result — that
     * absence is the removal, and it is why nothing here zeroes a quantity.
     */
    public Slot apply(ContainerId container, SlotId slotId, List<byte[]> photos, Decisions decisions) {
        requireBatch(photos);

        Slot existing = index.get(container, slotId)
            .orElse(new Slot(slotId, List.of(), null, List.of(), null));
        List<Item> held = existing.items() == null ? List.of() : existing.items();

        // Check every index before a single byte is stored: a stale decision must
        // fail without leaving photos on disk that no slot state points at.
        for (Keep keep : decisions.keep()) {
            if (keep.index() < 0 || keep.index() >= held.size()) {
                throw new IndexOutOfBoundsException(
                    "Item index " + keep.index() + " out of range (0.." + (held.size() - 1) + ")");
            }
        }

        // The capture page re-encodes every frame to JPEG on its way out, so the
        // batch is bytes without a type of its own by the time it reaches here.
        List<String> filenames = photos.stream()
            .map(p -> images.store(container, slotId, p, "image/jpeg"))
            .toList();

        List<Item> items = new ArrayList<>();
        for (Keep keep : decisions.keep()) {
            // No frame means the photos did not show this and someone kept it
            // anyway. Pointing it at a frame that does not show it would be a
            // small lie, and its old frames are about to be deleted — so it
            // keeps none, and the expanded view falls back to the slot's strip
            // under the heading that admits as much.
            items.add(refile(held.get(keep.index()), keep.qty(), frames(keep.frames(), filenames)));
        }
        for (Add add : decisions.add()) {
            // An added item came out of this very batch, so it is in one of
            // these frames even when the model did not say which.
            items.add(refile(add.item(), null, orFirst(frames(add.frames(), filenames), filenames)));
        }

        Slot updated = new Slot(existing.id(), List.copyOf(items), Instant.now(),
            List.copyOf(filenames), existing.printedAt());
        index.save(container, updated);

        // Only once the new state is recorded. A crash between the two leaves an
        // orphan file, which is nothing; the other order leaves the index naming
        // a photo that no longer exists, which is a broken drawer.
        List<String> before = existing.photos() == null ? List.of() : existing.photos();
        for (String old : before) {
            if (!filenames.contains(old)) images.delete(container, slotId, old);
        }

        return updated;
    }

    /**
     * Greedy one-to-one matching, best pair first. Returns, per held item, the
     * extraction it pairs with, or -1.
     *
     * <p>Greedy rather than optimal because the alternative buys nothing here: a
     * drawer holds a handful of items and a real ambiguity — two readings of the
     * same bag of bolts — is a question for the user, not for a better solver.
     * Taking the strongest pair first is what keeps a part number from being
     * spent on the wrong side of a near-tie in wording.
     */
    private static int[] pair(List<Item> current, List<Extraction> extractions) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            for (int j = 0; j < extractions.size(); j++) {
                double score = score(current.get(i), extractions.get(j).item());
                if (score > 0) candidates.add(new Candidate(i, j, score));
            }
        }
        // A stable sort, so equally good pairs are settled by the order they sit
        // in the drawer rather than by whatever the sort happened to do.
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));

        int[] pairedWith = new int[current.size()];
        Arrays.fill(pairedWith, -1);
        boolean[] spent = new boolean[extractions.size()];
        for (Candidate c : candidates) {
            if (pairedWith[c.held()] >= 0 || spent[c.found()]) continue;
            pairedWith[c.held()] = c.found();
            spent[c.found()] = true;
        }
        return pairedWith;
    }

    private static double score(Item held, Item found) {
        if (bothNumbered(held, found)) {
            // A drawer of parts is full of near-identical names: "BC547
            // transistor" and "BC557 transistor" share half their words, and
            // "100K resistors" and "82K resistors" share more. Where both sides
            // carry a part number it is the whole answer — equal is the same
            // part, different is a different part, and no amount of name
            // overlap should be allowed to argue otherwise.
            return samePartNumber(held, found) ? BY_PART_NUMBER : 0;
        }
        double overlap = overlap(words(label(held)), words(label(found)));
        return overlap >= SAME_ITEM ? overlap : 0;
    }

    private static boolean bothNumbered(Item held, Item found) {
        return notBlank(held.partNumber()) && notBlank(found.partNumber());
    }

    private static boolean samePartNumber(Item held, Item found) {
        return bothNumbered(held, found)
            && held.partNumber().trim().equalsIgnoreCase(found.partNumber().trim());
    }

    /** Items catalogued before name and description were split have only the description. */
    private static String label(Item item) {
        return notBlank(item.name()) ? item.name() : item.description();
    }

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) return words;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    private static double overlap(Set<String> held, Set<String> found) {
        if (held.isEmpty() || found.isEmpty()) return 0;
        int common = 0;
        for (String word : held) if (found.contains(word)) common++;
        return (double) common / Math.min(held.size(), found.size());
    }

    /** A quantity nobody estimated is one of the thing, not none of it. */
    private static int qty(Item item) {
        return item.qtyEstimate() == null ? 1 : item.qtyEstimate();
    }

    /** Everything but the quantity and the frames survives — that is the whole point. */
    private static Item refile(Item item, Integer qty, List<String> frames) {
        return new Item(item.name(), item.description(), item.partNumber(), item.category(),
            qty == null ? item.qtyEstimate() : qty, item.confidence(), item.tags(),
            item.embedding(), item.qa(),
            frames.isEmpty() ? null : frames.get(0),
            frames.isEmpty() ? null : frames);
    }

    private static List<Integer> framesOf(Extraction extraction, int photoCount) {
        List<Integer> frames = new ArrayList<>();
        for (int frame : extraction.imageIndexes()) {
            if (frame >= 0 && frame < photoCount) frames.add(frame);
        }
        // The model invents an index often enough to be worth pinning, and a
        // preview that names a frame the batch has not got renders as a hole.
        return frames.isEmpty() ? List.of(0) : List.copyOf(frames);
    }

    private static List<String> frames(List<Integer> frames, List<String> filenames) {
        List<String> named = new ArrayList<>();
        if (frames != null) {
            for (Integer frame : frames) {
                if (frame == null || frame < 0 || frame >= filenames.size()) continue;
                String filename = filenames.get(frame);
                if (!named.contains(filename)) named.add(filename);
            }
        }
        return List.copyOf(named);
    }

    /** For an item that must be in the batch somewhere, even if unnamed. */
    private static List<String> orFirst(List<String> frames, List<String> filenames) {
        return frames.isEmpty() ? List.of(filenames.get(0)) : frames;
    }

    private List<Item> currentItems(ContainerId container, SlotId slot) {
        return index.get(container, slot)
            .map(s -> s.items() == null ? List.<Item>of() : s.items())
            .orElse(List.of());
    }

    private static void requireBatch(List<byte[]> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean contains(int[] pairedWith, int found) {
        for (int paired : pairedWith) if (paired == found) return true;
        return false;
    }

    private record Candidate(int held, int found, double score) {}

    /**
     * {@code index} is the item's position in the slot's current list — what a
     * decision names it by — and {@code frames} are positions in the batch that
     * was just shot, not filenames, because nothing has been stored yet.
     */
    public record Preview(List<Matched> matched, List<Gone> gone, List<Added> added) {}

    public record Matched(int index, Item current, Item found, int qtyBefore, int qtyAfter, List<Integer> frames) {}

    public record Gone(int index, Item current) {}

    public record Added(Item found, List<Integer> frames) {}

    /** What the user confirmed. Anything absent from {@code keep} is being removed. */
    public record Decisions(List<Keep> keep, List<Add> add) {
        public Decisions {
            keep = keep == null ? List.of() : List.copyOf(keep);
            add = add == null ? List.of() : List.copyOf(add);
        }
    }

    /** A null {@code qty} leaves the recorded quantity alone. */
    public record Keep(int index, Integer qty, List<Integer> frames) {}

    public record Add(Item item, List<Integer> frames) {}
}
