package family.eilertsen.rack.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import family.eilertsen.rack.adapter.out.filesystem.FilesystemShoppingList;
import family.eilertsen.rack.application.ShopArrivals;
import family.eilertsen.rack.application.Shopping;
import family.eilertsen.rack.domain.port.HouseInbox;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShoppingEndpointTest {

    @TempDir Path dataDir;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
        FilesystemShoppingList list = new FilesystemShoppingList(dataDir.toString(), mapper);
        Shopping shopping = new Shopping(list, unused(PartIndex.class), unused(ImageStore.class));
        ShopArrivals arrivals = new ShopArrivals(list, new HouseInbox() {
            public boolean available() { return false; }
            public boolean tell(String owner, String text) { return false; }
        });
        mvc = MockMvcBuilders.standaloneSetup(new ShoppingController(shopping, arrivals))
            .setControllerAdvice(new DomainErrors())
            .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }

    @Test
    void saysWhetherArrivalsAreTold() throws Exception {
        mvc.perform(get("/shopping/notify")).andExpect(status().isOk()).andExpect(jsonPath("$.on").value(false));
    }

    @Test
    void addTickAndClear() throws Exception {
        String body = mvc.perform(multipart("/shopping")
                .param("what", "Wall plugs").param("store", "Leroy").param("part_number", "SX 6x30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.part_number").value("SX 6x30"))
            .andExpect(jsonPath("$.store").value("Leroy"))
            .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).get("id").asText();

        mvc.perform(patch("/shopping/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"bought\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bought_at").isNotEmpty());

        mvc.perform(delete("/shopping").param("bought", "true"))
            .andExpect(jsonPath("$.removed").value(1));
        mvc.perform(get("/shopping")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aBlankLineIsTheCallersMistake() throws Exception {
        mvc.perform(multipart("/shopping").param("what", "  "))
            .andExpect(status().isBadRequest());
    }

    @Test
    void aPhotoComesBackSmallWhenAsked() throws Exception {
        MockMultipartFile photo = new MockMultipartFile("photo", "p.jpg", "image/jpeg",
            family.eilertsen.rack.adapter.out.filesystem.FilesystemShoppingListTestSupport.jpeg());
        String body = mvc.perform(multipart("/shopping").file(photo).param("what", "Silicone"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String name = mapper.readTree(body).get("photo").asText();

        mvc.perform(get("/shopping/photos/" + name).param("w", "160")).andExpect(status().isOk());
        mvc.perform(get("/shopping/photos/nope.jpg")).andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
    }
}
