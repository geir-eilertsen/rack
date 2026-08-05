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
import java.util.List;

@Service
public class AddPhotoToSlot {

    private final ImageStore images;
    private final PartExtractor extractor;
    private final PartIndex index;

    public AddPhotoToSlot(ImageStore images, PartExtractor extractor, PartIndex index) {
        this.images = images;
        this.extractor = extractor;
        this.index = index;
    }

    /**
     * Files a batch of photos of one slot. Every photo is kept — the photo is
     * ground truth — but the batch is extracted in a single call so a part shot
     * from two angles is indexed once rather than twice.
     */
    public Result execute(ContainerId container, SlotId slot, List<Photo> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }

        List<String> filenames = photos.stream()
            .map(p -> images.store(container, slot, p.bytes(), p.contentType()))
            .toList();

        List<Extraction> extractions = extractor.extract(photos.stream().map(Photo::bytes).toList());
        List<Item> extracted = extractions.stream()
            .map(e -> stampSource(e.item(), framesOf(e, filenames)))
            .toList();

        Slot existing = index.get(container, slot).orElse(new Slot(slot, List.of(), null, List.of(), null));

        List<Item> mergedItems = new ArrayList<>(existing.items());
        mergedItems.addAll(extracted);
        List<String> mergedPhotos = new ArrayList<>(existing.photos());
        mergedPhotos.addAll(filenames);

        Slot updated = new Slot(slot, List.copyOf(mergedItems), Instant.now(), List.copyOf(mergedPhotos), existing.printedAt());
        index.save(container, updated);

        return new Result(filenames, extracted, alreadyHere(existing.items(), extracted));
    }

    /** Source stays the first frame, so the thumbnail is unchanged. */
    private static Item stampSource(Item i, List<String> frames) {
        return new Item(i.name(), i.description(), i.partNumber(), i.category(), i.qtyEstimate(),
            i.confidence(), i.tags(), i.embedding(), i.qa(), frames.get(0), frames);
    }

    private static List<String> framesOf(Extraction e, List<String> filenames) {
        List<String> frames = new ArrayList<>();
        for (int index : e.imageIndexes()) {
            if (index >= 0 && index < filenames.size()) frames.add(filenames.get(index));
        }
        return frames.isEmpty() ? List.of(filenames.get(0)) : List.copyOf(frames);
    }

    public record Photo(byte[] bytes, String contentType) {}

    /**
     * Ten more AA batteries into a drawer that already holds four is one thing
     * in two rows, and taking four out later decrements whichever row you
     * happened to tap. Filing still appends — the batteries really are in the
     * drawer, and a silent merge of two bags of black heat shrink that are not
     * the same bag would be worse than the duplicate. So it is reported, and
     * the page offers to join them.
     *
     * <p>Only the first match for each new item: a second candidate means the
     * drawer already had the thing twice, which is the same question again
     * rather than a different one.
     */
    private static List<Duplicate> alreadyHere(List<Item> held, List<Item> added) {
        List<Duplicate> duplicates = new ArrayList<>();
        for (int a = 0; a < added.size(); a++) {
            for (int h = 0; h < held.size(); h++) {
                if (!ItemMatch.sameThing(held.get(h), added.get(a))) continue;
                duplicates.add(new Duplicate(h, held.size() + a, held.get(h), added.get(a)));
                break;
            }
        }
        return List.copyOf(duplicates);
    }

    /** Both indexes are positions in the slot's item list as it now stands. */
    public record Duplicate(int existingIndex, int addedIndex, Item existing, Item added) {}

    public record Result(List<String> photoFilenames, List<Item> extracted, List<Duplicate> duplicates) {}
}
