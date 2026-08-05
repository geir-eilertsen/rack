package family.eilertsen.rack.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import family.eilertsen.rack.application.AddPhotoToSlot;
import family.eilertsen.rack.application.ContainerRegistry;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ContainerStore;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartExtractor;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The wire contract for a batch: repeated {@code photo} parts, one extraction call. */
class AddPhotoEndpointTest {

    private static final ContainerId RACK = new ContainerId("rack");

    private FakeImages images;
    private FakeExtractor extractor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        images = new FakeImages();
        extractor = new FakeExtractor();
        ContainerStore store = new ContainerStore() {
            @Override
            public List<Container> loadAll() {
                return List.of(new Container(RACK, "Rack", ContainerLayout.grid(2, 2), 1.0f));
            }

            @Override
            public void saveAll(List<Container> containers) {
            }
        };
        AddPhotoToSlot addPhoto = new AddPhotoToSlot(images, extractor, new FakeIndex());
        ContainerController controller = new ContainerController(
            new ContainerRegistry(store), new FakeIndex(), addPhoto,
            null, null, null, null, null, null, null, images);
        // Match the app's snake_case output so the assertions below are the
        // wire contract the browser sees, not a MockMvc default.
        ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }

    @Test
    void repeatedPhotoPartsAreFiledAsOneBatch() throws Exception {
        extractor.returns(new Extraction(item("bag of M4 bolts"), 1));

        mvc.perform(multipart("/c/rack/A1/photo")
                .file(part("front"))
                .file(part("label"))
                .file(part("side")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.photo_filenames.length()").value(3))
            .andExpect(jsonPath("$.extracted.length()").value(1));

        assertThat(images.stored).containsExactly("front", "label", "side");
        assertThat(extractor.calls).hasSize(1);
        assertThat(extractor.calls.get(0)).containsExactly("front", "label", "side");
    }

    @Test
    void aSinglePhotoIsStillAccepted() throws Exception {
        extractor.returns(new Extraction(item("BC547"), 0));

        mvc.perform(multipart("/c/rack/A1/photo").file(part("only")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.photo_filenames.length()").value(1));

        assertThat(extractor.calls.get(0)).containsExactly("only");
    }

    private static MockMultipartFile part(String marker) {
        return new MockMultipartFile("photo", marker + ".jpg", "image/jpeg", marker.getBytes(StandardCharsets.UTF_8));
    }

    private static Item item(String description) {
        return new Item(description, null, null, 1, 0.9, List.of(), null, List.of(), null);
    }

    private static final class FakeImages implements ImageStore {
        private final List<String> stored = new ArrayList<>();

        @Override
        public String store(ContainerId container, SlotId slot, byte[] image, String contentType) {
            String marker = new String(image, StandardCharsets.UTF_8);
            stored.add(marker);
            return marker + ".jpg";
        }

        @Override
        public byte[] read(ContainerId container, SlotId slot, String filename) {
            return new byte[0];
        }

        @Override
        public List<String> list(ContainerId container, SlotId slot) {
            return List.copyOf(stored);
        }
    }

    private static final class FakeExtractor implements PartExtractor {
        private final List<List<String>> calls = new ArrayList<>();
        private List<Extraction> result = List.of();

        void returns(Extraction... extractions) {
            this.result = List.of(extractions);
        }

        @Override
        public List<Extraction> extract(List<byte[]> images) {
            calls.add(images.stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList());
            return result;
        }
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<ContainerId, Map<SlotId, Slot>> slots = new LinkedHashMap<>();

        @Override
        public Optional<Slot> get(ContainerId container, SlotId slot) {
            return Optional.ofNullable(slots.getOrDefault(container, Map.of()).get(slot));
        }

        @Override
        public void save(ContainerId container, Slot slot) {
            slots.computeIfAbsent(container, k -> new LinkedHashMap<>()).put(slot.id(), slot);
        }

        @Override
        public Collection<Slot> all(ContainerId container) {
            return List.copyOf(slots.getOrDefault(container, Map.of()).values());
        }

        @Override
        public List<SearchHit> searchByKeyword(String query) {
            return List.of();
        }

        @Override
        public List<SearchHit> searchBySimilarity(float[] queryVector, int topK) {
            return List.of();
        }
    }
}
