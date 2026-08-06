package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.port.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The headers are the whole job of this endpoint, so they are what is asserted —
 * the first live call returned
 * {@code Content-Disposition: org.springframework.http.ContentDisposition$BuilderImpl@1d782749},
 * because toString() had been called on the builder rather than on what it builds.
 */
class DocumentEndpointTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FakeDocs docs = new FakeDocs();
        docs.put("Quad-606-service-manual.pdf", "%PDF-1.4 fake");
        docs.put("board.jpg", "jpegbytes");
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(docs)).build();
    }

    @Test
    void servesAPdfInlineWithAReadableDisposition() throws Exception {
        mvc.perform(get("/documents/Quad-606-service-manual.pdf"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"))
            // A manual is for reading at the bench, not for downloading a second
            // copy of every time you check a resistor value.
            .andExpect(header().string("Content-Disposition",
                "inline; filename=\"Quad-606-service-manual.pdf\""))
            .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void typesAnImageByItsExtension() throws Exception {
        mvc.perform(get("/documents/board.jpg"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"));
    }

    private static final class FakeDocs implements DocumentStore {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        void put(String name, String body) {
            files.put(name, body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String store(byte[] bytes, String originalFilename, String contentType) {
            put(originalFilename, new String(bytes, StandardCharsets.UTF_8));
            return originalFilename;
        }

        @Override
        public byte[] read(String filename) {
            byte[] found = files.get(filename);
            if (found == null) throw new NoSuchElementException(filename);
            return found;
        }

        @Override
        public List<String> all() {
            return List.copyOf(files.keySet());
        }

        @Override
        public void delete(String filename) {
            files.remove(filename);
        }
    }
}
