package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Deletes photographs nothing points at any more.
 *
 * <p>A frame no <em>item</em> names is kept on purpose — it is the evidence that
 * the extraction missed something, and 18 frames in this rack are named by no
 * item while plainly showing what is in the drawer. A frame no <em>slot</em>
 * names either is a different thing: nothing in the rack claims it is a picture
 * of anything, so it is a file taking up room.
 *
 * <p>Every path that can orphan one already cleans up after itself. This makes
 * the rule true by construction rather than by each of them remembering, and
 * catches whatever a hand-edit or an older version left behind.
 */
@Component
public class ForgetUnusedPhotos implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ForgetUnusedPhotos.class);

    private final PartIndex index;
    private final ImageStore images;

    public ForgetUnusedPhotos(PartIndex index, ImageStore images) {
        this.index = index;
        this.images = images;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    /** Returns the filenames it deleted. */
    public List<String> sweep() {
        Set<String> inUse = index.photosInUse();
        List<String> unused = images.all().stream().filter(name -> !inUse.contains(name)).toList();
        for (String name : unused) {
            // Named individually: deleting a photograph is not reversible, and
            // the log is the only record that it was this rack's own doing.
            log.info("Deleting photograph nothing references: {}", name);
            images.delete(name);
        }
        if (!unused.isEmpty()) log.info("Deleted {} unreferenced photograph(s)", unused.size());
        return unused;
    }
}
