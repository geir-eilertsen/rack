package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Drawer;
import family.eilertsen.rack.domain.model.DrawerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartExtractor;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AddPhotoToDrawer {

    private final ImageStore images;
    private final PartExtractor extractor;
    private final PartIndex index;

    public AddPhotoToDrawer(ImageStore images, PartExtractor extractor, PartIndex index) {
        this.images = images;
        this.extractor = extractor;
        this.index = index;
    }

    public Result execute(DrawerId drawer, byte[] photo, String contentType) {
        String filename = images.store(drawer, photo, contentType);
        List<Item> extracted = extractor.extract(photo);

        Drawer existing = index.get(drawer).orElse(new Drawer(drawer, List.of(), null, List.of()));

        List<Item> mergedItems = new ArrayList<>(existing.items());
        mergedItems.addAll(extracted);
        List<String> mergedPhotos = new ArrayList<>(existing.photos());
        mergedPhotos.add(filename);

        Drawer updated = new Drawer(drawer, List.copyOf(mergedItems), Instant.now(), List.copyOf(mergedPhotos));
        index.save(updated);

        return new Result(filename, extracted);
    }

    public record Result(String photoFilename, List<Item> extracted) {}
}
