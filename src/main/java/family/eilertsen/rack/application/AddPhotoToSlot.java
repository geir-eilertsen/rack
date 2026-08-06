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
import java.util.Set;

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
     * Files a batch of photos of one slot. The batch is extracted in a single
     * call so a part shot from two angles is indexed once rather than twice.
     *
     * <p>A frame the extraction attributed nothing to is dropped again. Items own
     * photographs, so such a frame has no owner — and the model was looking
     * straight at it when it found nothing there, which makes discarding it a
     * reading of the picture rather than a guess about it. Re-shoot beats keeping
     * a frame nothing on screen explains.
     */
    public Result execute(ContainerId container, SlotId slot, List<Photo> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("at least one photo is required");
        }

        List<String> filenames = photos.stream()
            .map(p -> images.store(p.bytes(), p.contentType()))
            .toList();

        List<Extraction> extractions = extractor.extract(photos.stream().map(Photo::bytes).toList());
        List<Item> extracted = extractions.stream()
            .map(e -> stampSource(e.item(), framesOf(e, filenames)))
            .toList();

        Slot existing = index.get(container, slot).orElse(new Slot(slot, List.of(), null, null));

        List<Item> mergedItems = new ArrayList<>(existing.items());
        mergedItems.addAll(extracted);

        Slot updated = new Slot(slot, List.copyOf(mergedItems), Instant.now(), existing.printedAt());
        index.save(container, updated);

        // After the write, and against the whole index: one of these frames may
        // already belong to an item somewhere else if the same picture was filed
        // twice, and the batch that just landed is the least of what it shows.
        List<String> spoken = new ArrayList<>();
        Set<String> inUse = index.photosInUse();
        for (String filename : filenames) {
            if (inUse.contains(filename)) spoken.add(filename);
            else images.delete(filename);
        }

        return new Result(List.copyOf(spoken), extracted, filenames.size() - spoken.size());
    }

    /** Source stays the first frame, so the thumbnail is unchanged. */
    private static Item stampSource(Item i, List<String> frames) {
        return new Item(i.name(), i.description(), i.partNumber(), i.category(), i.qtyEstimate(),
            i.confidence(), i.tags(), i.qa(), frames.get(0), frames);
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
     * {@code discarded} is how many frames of the batch nothing was read from and
     * so were not kept. Reported rather than merely done: a frame going quiet is
     * the one case where the user needs to know to shoot it again.
     */
    public record Result(List<String> photoFilenames, List<Item> extracted, int discarded) {}
}
