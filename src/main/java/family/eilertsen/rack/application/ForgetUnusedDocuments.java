package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.port.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A document nothing points at is deleted, the way an unreferenced photograph is.
 * Sweeps at boot so the rule holds by construction rather than by every path that
 * can orphan a file remembering to clean up.
 */
@Component
public class ForgetUnusedDocuments implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ForgetUnusedDocuments.class);

    private final KeepDocuments keep;
    private final DocumentStore documents;

    public ForgetUnusedDocuments(KeepDocuments keep, DocumentStore documents) {
        this.keep = keep;
        this.documents = documents;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    public List<String> sweep() {
        Set<String> used = keep.inUse();
        List<String> gone = new ArrayList<>();
        for (String filename : documents.all()) {
            if (used.contains(filename)) continue;
            documents.delete(filename);
            gone.add(filename);
            log.info("Deleting document nothing references: {}", filename);
        }
        if (!gone.isEmpty()) log.info("Deleted {} unreferenced document(s)", gone.size());
        return List.copyOf(gone);
    }
}
