package family.eilertsen.rack.adapter.out.cache;

import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a batch of photos once, however many times it is asked.
 *
 * <p>Filing a slot the app suggested took two vision calls over the same
 * photographs: {@code /suggest} reads them to work out which drawer they belong
 * in, and the file that follows reads them again to record what is in them.
 * That is twice the cost and twice the wait for one answer, and the second
 * reading can disagree with the first — the user picks a drawer on the strength
 * of one reading and files a different one.
 *
 * <p>Keyed on the bytes rather than on a token the caller has to carry, so any
 * two readings of the same batch collapse without either caller knowing this
 * exists. Deliberately small and short-lived: it exists to join up one user
 * action, not to spare anyone a re-read an hour later.
 */
@Component
@Primary
public class CachingPartExtractor implements PartExtractor {

    private static final Logger log = LoggerFactory.getLogger(CachingPartExtractor.class);

    /** A handful of in-flight batches. One user, one drawer at a time. */
    private static final int KEEP = 8;

    private final PartExtractor delegate;

    private final Map<String, List<Extraction>> byBatch = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Extraction>> eldest) {
                return size() > KEEP;
            }
        });

    public CachingPartExtractor(@Qualifier("springAiPartExtractor") PartExtractor delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Extraction> extract(List<byte[]> images) {
        String key = fingerprint(images);
        if (key == null) return delegate.extract(images);

        List<Extraction> cached = byBatch.get(key);
        if (cached != null) {
            log.debug("Reusing the reading of a batch of {} photo(s)", images.size());
            return cached;
        }
        List<Extraction> extracted = delegate.extract(images);
        byBatch.put(key, extracted);
        return extracted;
    }

    /** Null when the digest is unavailable — then it is simply not cached. */
    private static String fingerprint(List<byte[]> images) {
        if (images == null || images.isEmpty()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] image : images) {
                // Length as well as content: without it two batches whose frames
                // are the same bytes differently divided would collide.
                digest.update(Integer.toString(image.length).getBytes());
                digest.update(image);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
