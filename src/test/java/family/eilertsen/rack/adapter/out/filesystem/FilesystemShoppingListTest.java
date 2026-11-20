package family.eilertsen.rack.adapter.out.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import family.eilertsen.rack.application.Shopping;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.model.ShoppingEntry;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.ImageStore;
import family.eilertsen.rack.domain.port.PartIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemShoppingListTest {

    private static final ContainerId GARAGE = new ContainerId("garage");
    private static final SlotId A1 = new SlotId("A1");

    @TempDir Path dataDir;
    private ObjectMapper mapper;
    private FakeIndex index;
    private FakeImages images;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
        index = new FakeIndex();
        images = new FakeImages();
    }

    private FilesystemShoppingList store() throws IOException {
        return new FilesystemShoppingList(dataDir.toString(), mapper);
    }

    @Test
    void anEntryFromAnItemCarriesItsPictureAndPartNumber() throws IOException {
        index.put(GARAGE, new Slot(A1, List.of(item("Wall plugs", "Grey nylon plugs, 6×30mm, Fischer SX", "SX 6x30", "plugs.jpg")), null, null));
        FilesystemShoppingList list = store();
        Shopping shopping = new Shopping(list, index, images);

        ShoppingEntry e = shopping.addFromItem(GARAGE, A1, 0, "Leroy", "1 box", "the grey ones, not the red");

        assertThat(e.what()).isEqualTo("Wall plugs");
        assertThat(e.partNumber()).isEqualTo("SX 6x30");
        assertThat(e.store()).isEqualTo("Leroy");
        assertThat(e.details()).startsWith("the grey ones, not the red").contains("Fischer SX");
        assertThat(e.container()).isEqualTo("garage");
        assertThat(e.slot()).isEqualTo("A1");
        assertThat(e.photo()).isNotNull();
        assertThat(list.photo(e.photo())).isNotEmpty();
    }

    @Test
    void thePictureOutlivesTheItem() throws IOException {
        // Running out is why it is on the list: the item going must not take the
        // picture of what to buy with it.
        index.put(GARAGE, new Slot(A1, List.of(item("Wall plugs", "Grey", null, "plugs.jpg")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        ShoppingEntry e = shopping.addFromItem(GARAGE, A1, 0, null, null, null);

        index.put(GARAGE, new Slot(A1, List.of(), null, null));
        images.gone = true;

        Shopping reopened = new Shopping(store(), index, images);
        assertThat(reopened.all()).extracting(ShoppingEntry::id).containsExactly(e.id());
        assertThat(reopened.photo(e.photo())).isNotEmpty();
    }

    @Test
    void survivesARestartAndTicksOff() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        ShoppingEntry e = shopping.add(new Shopping.Text("Silicone, white", "Biltema", null, null, "2"), jpeg(), null);
        shopping.setBought(e.id(), true);

        Shopping reopened = new Shopping(store(), index, images);
        ShoppingEntry back = reopened.all().get(0);
        assertThat(back.what()).isEqualTo("Silicone, white");
        assertThat(back.qty()).isEqualTo("2");
        assertThat(back.bought()).isTrue();
        assertThat(back.photo()).isEqualTo(e.photo());
    }

    @Test
    void clearingBoughtTakesTheirPictures() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        ShoppingEntry bought = shopping.add(new Shopping.Text("Fuse 10A", null, null, null, null), jpeg(), null);
        ShoppingEntry still = shopping.add(new Shopping.Text("Fuse 16A", null, null, null, null), jpeg(), null);
        shopping.setBought(bought.id(), true);

        assertThat(shopping.clearBought()).isEqualTo(1);

        assertThat(shopping.all()).extracting(ShoppingEntry::id).containsExactly(still.id());
        assertThat(Files.exists(dataDir.resolve("shopping-photos").resolve(bought.photo()))).isFalse();
        assertThat(Files.exists(dataDir.resolve("shopping-photos").resolve(still.photo()))).isTrue();
    }

    @Test
    void aPictureNothingNamesIsSweptAtBoot() throws IOException {
        Files.createDirectories(dataDir.resolve("shopping-photos"));
        Files.write(dataDir.resolve("shopping-photos/stray.jpg"), new byte[] {1});
        store();
        assertThat(Files.exists(dataDir.resolve("shopping-photos/stray.jpg"))).isFalse();
    }

    @Test
    void theRackStillLoadsBesideAFullList() throws Exception {
        // Every directory under data/ is a container to the index and its *.json
        // are slots. The first deploy kept the list in data/shopping/ and the
        // index read list.json as a slot and refused to boot.
        Shopping shopping = new Shopping(store(), index, images);
        shopping.add(new Shopping.Text("Fuse", "Jula", null, null, null), jpeg(), null);
        shopping.setBrand("Anslut", "Jula");

        family.eilertsen.rack.adapter.out.json.JsonFilePartIndex rack =
            new family.eilertsen.rack.adapter.out.json.JsonFilePartIndex(dataDir.toString(), mapper);
        rack.load();
        try (var dirs = Files.list(dataDir)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(d)) {
                    assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.endsWith(".json"));
                }
            }
        }
    }

    @Test
    void shopSpotsSurviveARestartAndCanBeForgotten() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        var jula = shopping.savePlace("Jula", 59.91, 10.75, 12.0);
        shopping.savePlace("Jula", 60.39, 5.32, null);       // another branch, same name
        shopping.forgetPlace(jula.id());

        var back = new Shopping(store(), index, images).places();
        assertThat(back).hasSize(1);
        assertThat(back.get(0).store()).isEqualTo("Jula");
        assertThat(back.get(0).lat()).isEqualTo(60.39);
    }

    @Test
    void aSpotHasToBeSomewhere() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        assertThatThrownBy(() -> shopping.savePlace("Jula", 91, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> shopping.savePlace(" ", 59, 10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(shopping.places()).isEmpty();
    }

    @Test
    void refusesAPathForAPhotoName() throws IOException {
        FilesystemShoppingList list = store();
        assertThatThrownBy(() -> list.photo("../list.json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntryHasToSayWhat() {
        assertThatThrownBy(() -> new ShoppingEntry("abc", "  ", null, null, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHouseBrandNamesTheStore() throws IOException {
        // Anslut is Jula's own brand and the box says Anslut, not Jula.
        index.put(GARAGE, new Slot(A1, List.of(
            item("Anslut GU10 LED bulb", "Anslut brand dimmable GU10 LED reflector lamp, 5W", null, "gu10.jpg"),
            item("Lexman E27 bulb", "Lexman LED bulb, E27", null, "e27.jpg"),
            item("Sylvania HI-SPOT ES50 bulb", "Sylvania halogen spotlight, GU10", null, "es50.jpg")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setBrand("Anslut", "Jula");
        shopping.setBrand("Lexman", "Leroy");

        assertThat(shopping.addFromItem(GARAGE, A1, 0, null, null, null).store()).isEqualTo("Jula");
        assertThat(shopping.addFromItem(GARAGE, A1, 1, "", null, null).store()).isEqualTo("Leroy");
        assertThat(shopping.addFromItem(GARAGE, A1, 2, null, null, null).store()).isNull();
        // What the person typed wins over what the brand says.
        assertThat(shopping.addFromItem(GARAGE, A1, 0, "Clas Ohlson", null, null).store()).isEqualTo("Clas Ohlson");
    }

    @Test
    void aStoreTheItemNamesIsItsOwnAnswer() throws IOException {
        index.put(GARAGE, new Slot(A1, List.of(
            item("Capacitor", "Panasonic 100uF, Farnell 876-7670", null, "c.jpg"),
            item("Fuse", "Farnellish fuse", null, "f.jpg")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        shopping.add(new Shopping.Text("Solder", "Farnell", null, null, null), null, null);

        assertThat(shopping.storeFor(GARAGE, A1, 0)).map(Shopping.StoreHint::store).contains("Farnell");
        assertThat(shopping.storeFor(GARAGE, A1, 1)).isEmpty();   // whole words only
    }

    @Test
    void theRecordedBrandNamesTheStoreWhenTheWordsDoNot() throws IOException {
        // The extractor read "Anslut" off the box; the name and description it
        // wrote do not say it again.
        index.put(GARAGE, new Slot(A1, List.of(
            branded("GU10 LED bulb", "Dimmable reflector lamp, 5W", "Anslut Pro"),
            branded("Wall plugs", "Grey nylon, 6mm", "Fischer")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setBrand("anslut", "Jula");

        assertThat(shopping.storeFor(GARAGE, A1, 0)).map(Shopping.StoreHint::store).contains("Jula");
        assertThat(shopping.hintFor(GARAGE, A1, 0)).map(Shopping.StoreHint::stores).contains(List.of("Jula"));
        // A brand with no shop yet is offered to be remembered, and not guessed.
        assertThat(shopping.hintFor(GARAGE, A1, 1))
            .hasValueSatisfying(h -> {
                assertThat(h.store()).isNull();
                assertThat(h.stores()).isEmpty();
                assertThat(h.brand()).isEqualTo("Fischer");
            });
    }

    @Test
    void namingTheShopForAnUnplacedBrandRemembersIt() throws IOException {
        index.put(GARAGE, new Slot(A1, List.of(
            branded("Wall plugs", "Grey nylon, 6mm", "Fischer"),
            branded("Wall plugs", "Grey nylon, 8mm", "Fischer")), null, null));
        Shopping shopping = new Shopping(store(), index, images);

        shopping.addFromItem(GARAGE, A1, 0, "Biltema", null, null, true);
        assertThat(shopping.brands()).containsExactly(new family.eilertsen.rack.domain.model.BrandStore("Fischer", "Biltema"));
        assertThat(shopping.addFromItem(GARAGE, A1, 1, null, null, null).store()).isEqualTo("Biltema");

        // A second shop is added beside the first, never in its place; naming
        // one already known writes nothing twice.
        shopping.addFromItem(GARAGE, A1, 0, "Jula", null, null, true);
        shopping.addFromItem(GARAGE, A1, 0, "biltema", null, null, true);
        assertThat(shopping.brands()).containsExactly(
            new family.eilertsen.rack.domain.model.BrandStore("Fischer", "Biltema"),
            new family.eilertsen.rack.domain.model.BrandStore("Fischer", "Jula"));
    }

    @Test
    void withoutAskingNothingIsRemembered() throws IOException {
        index.put(GARAGE, new Slot(A1, List.of(branded("Wall plugs", "6mm", "Fischer")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        shopping.addFromItem(GARAGE, A1, 0, "Biltema", null, null);
        assertThat(shopping.brands()).isEmpty();
    }

    @Test
    void knownBrandsAreWhatItemsAndRulesSayOnceEachInTheCommonSpelling() throws IOException {
        index.put(GARAGE, new Slot(A1, List.of(
            branded("GU10 bulb", "5W", "Anslut"),
            branded("E27 bulb", "8W", "Anslut"),
            branded("Plug", "6mm", " anslut "),
            branded("Fuse", "10A", null)), null, null));
        FilesystemShoppingList list = store();
        new Shopping(list, index, images).setBrand("Lexman", "Leroy");
        var registry = new family.eilertsen.rack.application.ContainerRegistry(
            new family.eilertsen.rack.domain.port.ContainerStore() {
                @Override public List<family.eilertsen.rack.domain.model.Container> loadAll() {
                    return List.of(new family.eilertsen.rack.domain.model.Container(GARAGE, "Garage",
                        family.eilertsen.rack.domain.model.ContainerLayout.linear(1, null), 1.0f, null, null, null));
                }
                @Override public void saveAll(List<family.eilertsen.rack.domain.model.Container> c) {}
            });

        assertThat(new family.eilertsen.rack.application.KnownBrands(registry, index, list).all())
            .containsExactly("Anslut", "Lexman");
    }

    @Test
    void knownStoresComeFromLinesRulesAndSpotsOnceEach() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        shopping.add(new Shopping.Text("Fuse", "Jula", null, null, null), null, null);
        shopping.add(new Shopping.Text("Plug", "jula ", null, null, null), null, null);
        shopping.setBrand("Anslut", "Jula");
        shopping.setBrand("Lexman", "Leroy");
        shopping.savePlace("Biltema", 59.9, 10.7, 20.0);
        // Bought and cleared: the line goes, the store it named should not.
        ShoppingEntry gone = shopping.add(new Shopping.Text("Solder", "Farnell", null, null, null), null, null);
        shopping.setBought(gone.id(), true);
        shopping.clearBought();
        shopping.setBrand("Multicore", "Farnell");

        assertThat(shopping.stores()).containsExactly("Biltema", "Farnell", "Jula", "Leroy");
    }

    @Test
    void anOnlineShopAndItsSearchSurviveARestartUnderTheSpellingInUse() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setShop("Amazon", true, null);
        shopping.setShop("amazon", true, "https://www.amazon.de/s?k={q}");
        shopping.setShop("Jula", false, "https://www.jula.no/search?query={q}");

        Shopping reopened = new Shopping(store(), index, images);
        assertThat(reopened.shops()).containsExactly(
            new family.eilertsen.rack.domain.model.Shop("Amazon", true, "https://www.amazon.de/s?k={q}"),
            new family.eilertsen.rack.domain.model.Shop("Jula", false, "https://www.jula.no/search?query={q}"));
        assertThat(reopened.stores()).containsExactly("Amazon", "Jula");

        reopened.forgetShop("JULA");
        assertThat(reopened.shops()).extracting(family.eilertsen.rack.domain.model.Shop::name).containsExactly("Amazon");
    }

    @Test
    void aSearchAddressIsAWebAddressWithSomewhereForTheWords() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        assertThatThrownBy(() -> shopping.setShop("Evil", true, "javascript:alert('{q}')"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> shopping.setShop("Amazon", true, "https://www.amazon.de/"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{q}");
        assertThat(shopping.shops()).isEmpty();
    }

    @Test
    void anOnlineShopHasNoSpotToSave() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setShop("Amazon", true, null);
        assertThatThrownBy(() -> shopping.savePlace("amazon", 59.9, 10.7, 10.0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("online");
        assertThat(shopping.savePlace("Jula", 59.9, 10.7, 10.0).store()).isEqualTo("Jula");
    }

    @Test
    void anOrderIsOnItsWayUntilItArrives() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        ShoppingEntry e = shopping.add(new Shopping.Text("Flux pen", "Amazon", null, null, null), null, null);
        assertThat(e.open()).isTrue();

        ShoppingEntry ordered = shopping.setOrdered(e.id(), true, "302-1234567");
        assertThat(ordered.ordered()).isTrue();
        assertThat(ordered.open()).isFalse();
        // Saying it again — adding a tracking link — keeps when it was ordered.
        ShoppingEntry again = shopping.setOrdered(e.id(), true, "https://track.example/302");
        assertThat(again.orderedAt()).isEqualTo(ordered.orderedAt());
        assertThat(again.orderRef()).isEqualTo("https://track.example/302");

        ShoppingEntry arrived = shopping.setBought(e.id(), true);
        assertThat(arrived.ordered()).isFalse();
        assertThat(arrived.bought()).isTrue();

        ShoppingEntry back = new Shopping(store(), index, images).all().get(0);
        assertThat(back.orderedAt()).isEqualTo(ordered.orderedAt());
        assertThat(back.orderRef()).isEqualTo("https://track.example/302");

        assertThat(shopping.setOrdered(e.id(), false, null).orderRef()).isNull();
    }

    @Test
    void aBrandSoldInSeveralShopsOffersThemAll() throws IOException {
        index.put(GARAGE, new Slot(A1, List.of(
            branded("Logitech wireless mouse", "laser, nano receiver", "Logitech")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setBrand("Logitech", "Komplett");
        shopping.setBrand("logitech", "Amazon.es");
        shopping.setBrand("Logitech", "komplett");          // already known: nothing new

        assertThat(shopping.brands()).hasSize(2);
        assertThat(shopping.hintFor(GARAGE, A1, 0)).hasValueSatisfying(h -> {
            assertThat(h.store()).isEqualTo("Komplett");
            assertThat(h.stores()).containsExactly("Komplett", "Amazon.es");
            assertThat(h.because()).isEqualTo("Logitech is sold at Komplett and Amazon.es");
        });
        // Nothing to pick: the line goes on the list for every shop that sells it.
        ShoppingEntry both = shopping.addFromItem(GARAGE, A1, 0, null, null, null);
        assertThat(both.stores()).containsExactly("Komplett", "Amazon.es");
        // A shop typed as well comes first, and the others are kept.
        ShoppingEntry three = shopping.addFromItem(GARAGE, A1, 0, List.of("Elkjøp"), null, null, true);
        assertThat(three.stores()).containsExactly("Elkjøp", "Komplett", "Amazon.es");
        assertThat(shopping.brands()).hasSize(3);           // and Elkjøp is remembered beside them

        // Survives a restart, and every one of them is a known store.
        Shopping reopened = new Shopping(store(), index, images);
        // Elkjøp, learned on the second line, reached the first one too.
        assertThat(reopened.all().get(0).stores()).containsExactly("Komplett", "Amazon.es", "Elkjøp");
        assertThat(reopened.stores()).contains("Amazon.es", "Elkjøp", "Komplett");

        // Ordered from one, it narrows to that one.
        ShoppingEntry ordered = reopened.setOrdered(both.id(), true, "302-1", "amazon.es");
        assertThat(ordered.stores()).containsExactly("amazon.es");
        assertThat(ordered.ordered()).isTrue();
    }

    @Test
    void aLineForSeveralShopsKeepsEachOnce() {
        ShoppingEntry e = new ShoppingEntry("x1", "Mouse", null, null, null, null, null, null, null,
            java.time.Instant.EPOCH, null, null, null, List.of(" Komplett", "komplett", "", "Amazon.es"));
        assertThat(e.store()).isEqualTo("Komplett");
        assertThat(e.alsoAt()).containsExactly("Amazon.es");
        assertThat(e.soldAt("amazon.es ")).isTrue();
        assertThat(e.soldAt("Jula")).isFalse();
    }

    @Test
    void aBrandRuleWrittenAfterTheLineStillReachesIt() throws IOException {
        // Filed before brands were read: the brand is only in the description.
        index.put(GARAGE, new Slot(A1, List.of(
            item("CR2016 coin cell batteries", "Energizer CR2016 lithium 3V coin cells", null, "cr.jpg"),
            branded("AA batteries", "alkaline, 4-pack", "Energizer"),
            item("Wall plugs", "6mm nylon", null, "wp.jpg")), null, null));
        Shopping shopping = new Shopping(store(), index, images);
        ShoppingEntry coins = shopping.addFromItem(GARAGE, A1, 0, "Jula", null, null);
        ShoppingEntry aa = shopping.addFromItem(GARAGE, A1, 1, null, null, null);
        ShoppingEntry plugs = shopping.addFromItem(GARAGE, A1, 2, null, null, null);
        ShoppingEntry typed = shopping.add(new Shopping.Text("Energizer 9V", null, null, null, null), null, null);
        ShoppingEntry gone = shopping.addFromItem(GARAGE, A1, 1, "Biltema", null, null);
        shopping.setBought(gone.id(), true);

        shopping.setBrand("energizer", "Leroy");

        Map<String, ShoppingEntry> now = new HashMap<>();
        shopping.all().forEach(e -> now.put(e.id(), e));
        assertThat(now.get(coins.id()).stores()).containsExactly("Jula", "Leroy");   // beside, not instead
        assertThat(now.get(aa.id()).stores()).containsExactly("Leroy");              // anywhere, now Leroy
        assertThat(now.get(typed.id()).stores()).containsExactly("Leroy");           // typed in, named in its text
        assertThat(now.get(plugs.id()).stores()).isEmpty();                          // not of the brand
        assertThat(now.get(gone.id()).stores()).containsExactly("Biltema");          // already bought
    }

    @Test
    void aPhotoForALineIsReadAndItsBrandSaysWhichShops() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setBrand("Energizer", "Leroy");
        shopping.setBrand("Energizer", "Jula");
        Item cells = new Item("CR2016 coin cells", "Energizer lithium 3V, blister of 2", "CR2016", "Energizer",
            "other", 2, 0.92, List.of(), null, null, null, List.of());
        Item blur = new Item("battery", "something round", null, null, "other", 1, 0.3, List.of(), null, null, null, List.of());
        family.eilertsen.rack.domain.port.PartExtractor extractor = (photos, note) -> List.of(
            new family.eilertsen.rack.domain.model.Extraction(blur, 0),
            new family.eilertsen.rack.domain.model.Extraction(cells, 0));

        var reading = new family.eilertsen.rack.application.ReadForShopping(extractor, shopping).read(jpeg());

        // The one it was surest about, with the shops its brand is sold at.
        assertThat(reading.what()).isEqualTo("CR2016 coin cells");
        assertThat(reading.partNumber()).isEqualTo("CR2016");
        assertThat(reading.brand()).isEqualTo("Energizer");
        assertThat(reading.details()).isEqualTo("Energizer lithium 3V, blister of 2");
        assertThat(reading.stores()).containsExactly("Leroy", "Jula");
        assertThat(reading.others()).isEqualTo(1);
        assertThat(shopping.all()).isEmpty();                    // reading writes nothing
    }

    @Test
    void aTypedLineWithABrandGoesToEveryShopThatSellsIt() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setBrand("Energizer", "Leroy");
        shopping.setBrand("Energizer", "Jula");

        ShoppingEntry plain = shopping.add(new Shopping.Text("CR2016", null, null, null, "2", "energizer"), null, null);
        ShoppingEntry typed = shopping.add(new Shopping.Text("CR2032", "Biltema", null, null, null, "Energizer"), null, null);

        assertThat(plain.stores()).containsExactly("Leroy", "Jula");
        assertThat(plain.brand()).isEqualTo("energizer");
        assertThat(typed.stores()).containsExactly("Biltema", "Leroy", "Jula");
    }

    @Test
    void aPhotoTheCameraCannotReadSaysSo() {
        Shopping shopping = new Shopping(null, index, images);
        var reader = new family.eilertsen.rack.application.ReadForShopping((photos, note) -> List.of(), shopping);
        assertThatThrownBy(() -> reader.read(new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
    }

    private static Item branded(String name, String description, String brand) {
        return new Item(name, description, null, brand, null, 3, 0.9, List.of(), List.of(), "b.jpg",
            List.of("b.jpg"), List.of());
    }

    @Test
    void brandRulesSurviveARestartAndCanBeCorrected() throws IOException {
        Shopping shopping = new Shopping(store(), index, images);
        shopping.setBrand("Anslut", "Clas Ohlson");
        shopping.setBrand("anslut", "Jula");
        shopping.setBrand("Lexman", "Leroy");
        shopping.forgetBrand("LEXMAN");
        shopping.forgetBrand("anslut", "clas ohlson");

        assertThat(new Shopping(store(), index, images).brands())
            .containsExactly(new family.eilertsen.rack.domain.model.BrandStore("Anslut", "Jula"));
    }

    private static Item item(String name, String description, String partNumber, String photo) {
        return new Item(name, description, partNumber, null, 3, 0.9, List.of(), List.of(), photo, List.of(photo), List.of());
    }

    static byte[] jpeg() throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static final class FakeImages implements ImageStore {
        boolean gone;
        @Override public String store(byte[] image, String contentType) { throw new UnsupportedOperationException(); }
        @Override public byte[] read(String filename) {
            if (gone) throw new java.io.UncheckedIOException(new IOException("gone"));
            try { return jpeg(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }
        @Override public byte[] thumbnail(String filename, int maxEdge) { return read(filename); }
        @Override public List<String> all() { return List.of(); }
        @Override public void delete(String filename) {}
    }

    private static final class FakeIndex implements PartIndex {
        private final Map<String, Slot> slots = new HashMap<>();
        void put(ContainerId c, Slot s) { slots.put(c.value() + "/" + s.id().value(), s); }
        @Override public Optional<Slot> get(ContainerId c, SlotId s) { return Optional.ofNullable(slots.get(c.value() + "/" + s.value())); }
        @Override public void save(ContainerId c, Slot s) { put(c, s); }
        @Override public Collection<Slot> all(ContainerId c) { return slots.values(); }
        @Override public void forget(ContainerId c) {}
        @Override public List<SearchHit> searchByKeyword(String q) { return List.of(); }
        @Override public Set<String> vocabulary() { return Set.of(); }
        @Override public Set<String> photosInUse() { return Set.of(); }
        @Override public Set<String> documentsInUse() { return Set.of(); }
    }
}
