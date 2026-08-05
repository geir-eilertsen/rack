package family.eilertsen.rack.adapter.out.cache;

import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachingPartExtractorTest {

    private CountingExtractor vision;
    private CachingPartExtractor extractor;

    @BeforeEach
    void setUp() {
        vision = new CountingExtractor();
        extractor = new CachingPartExtractor(vision);
    }

    @Test
    void readsOneBatchOnceHoweverManyTimesItIsAsked() {
        // Suggesting a drawer and then filing it are two requests over the same
        // photographs. Reading them twice costs twice and, worse, can answer
        // differently — the drawer is picked on one reading and filed with
        // another.
        extractor.extract(batch("front", "label"));
        extractor.extract(batch("front", "label"));

        assertThat(vision.calls).isEqualTo(1);
    }

    @Test
    void aDifferentBatchIsADifferentReading() {
        extractor.extract(batch("front"));
        extractor.extract(batch("side"));

        assertThat(vision.calls).isEqualTo(2);
    }

    @Test
    void theSameFramesInADifferentOrderAreADifferentBatch() {
        // Order is what image_indexes point into, so a reading of one order
        // cannot be handed to the other.
        extractor.extract(batch("front", "label"));
        extractor.extract(batch("label", "front"));

        assertThat(vision.calls).isEqualTo(2);
    }

    @Test
    void framesThatDifferOnlyInWhereTheySplitAreNotTheSameBatch() {
        // Without the length in the digest, ["ab","c"] and ["a","bc"] hash the
        // same and one drawer is filed with another's reading.
        extractor.extract(List.of("ab".getBytes(StandardCharsets.UTF_8), "c".getBytes(StandardCharsets.UTF_8)));
        extractor.extract(List.of("a".getBytes(StandardCharsets.UTF_8), "bc".getBytes(StandardCharsets.UTF_8)));

        assertThat(vision.calls).isEqualTo(2);
    }

    @Test
    void anOldBatchIsForgottenRatherThanHeldForever() {
        for (int i = 0; i < 10; i++) extractor.extract(batch("batch " + i));
        extractor.extract(batch("batch 0"));

        assertThat(vision.calls).isEqualTo(11);
    }

    @Test
    void passesAnEmptyBatchStraightThroughToBeRefused() {
        extractor.extract(List.of());

        assertThat(vision.calls).isEqualTo(1);
    }

    private static List<byte[]> batch(String... markers) {
        List<byte[]> photos = new ArrayList<>();
        for (String marker : markers) photos.add(marker.getBytes(StandardCharsets.UTF_8));
        return photos;
    }

    private static final class CountingExtractor implements PartExtractor {
        private int calls;

        @Override
        public List<Extraction> extract(List<byte[]> images) {
            calls++;
            return List.of(new Extraction(
                new Item("thing", "a thing", null, "other", 1, 0.9, List.of(), null, List.of(), null, null), 0));
        }
    }
}
