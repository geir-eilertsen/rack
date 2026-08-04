package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
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

    public Result execute(ContainerId container, SlotId slot, byte[] photo, String contentType) {
        String filename = images.store(container, slot, photo, contentType);
        List<Item> extracted = extractor.extract(photo);

        Slot existing = index.get(container, slot).orElse(new Slot(slot, List.of(), null, List.of(), null));

        List<Item> mergedItems = new ArrayList<>(existing.items());
        mergedItems.addAll(extracted);
        List<String> mergedPhotos = new ArrayList<>(existing.photos());
        mergedPhotos.add(filename);

        Slot updated = new Slot(slot, List.copyOf(mergedItems), Instant.now(), List.copyOf(mergedPhotos), existing.printedAt());
        index.save(container, updated);

        return new Result(filename, extracted);
    }

    public record Result(String photoFilename, List<Item> extracted) {}
}
