package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.ShopArrivals;
import family.eilertsen.rack.application.Shopping;
import family.eilertsen.rack.domain.model.BrandStore;
import family.eilertsen.rack.domain.model.ShopPlace;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Framing;
import family.eilertsen.rack.domain.model.ShoppingEntry;
import family.eilertsen.rack.domain.model.SlotId;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
public class ShoppingController {

    private final Shopping shopping;

    private final ShopArrivals arrivals;

    public ShoppingController(Shopping shopping, ShopArrivals arrivals) {
        this.shopping = shopping;
        this.arrivals = arrivals;
    }

    public record Notify(boolean on) {}

    /** Whether rack can tell a phone it has arrived at a shop (through Vaier). */
    @GetMapping("/shopping/notify")
    public Notify notifyStatus() {
        return new Notify(arrivals.on());
    }

    @GetMapping("/shopping")
    public List<ShoppingEntry> all() {
        return shopping.all();
    }

    /** Multipart, so a picture can come with it. */
    @PostMapping(value = "/shopping", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ShoppingEntry add(@RequestParam("what") String what,
                             @RequestParam(value = "store", required = false) String store,
                             @RequestParam(value = "part_number", required = false) String partNumber,
                             @RequestParam(value = "details", required = false) String details,
                             @RequestParam(value = "qty", required = false) String qty,
                             @RequestParam(value = "photo", required = false) MultipartFile photo,
                             @RequestParam(value = "seen", required = false) String seen) throws IOException {
        byte[] bytes = photo == null || photo.isEmpty() ? null : photo.getBytes();
        return shopping.add(new Shopping.Text(what, store, partNumber, details, qty), bytes, Framing.parse(seen));
    }

    public record FromItem(String store, String qty, String note, Boolean rememberBrand) {}

    @PostMapping("/c/{container}/{slot}/items/{index}/shopping")
    public ShoppingEntry addFromItem(@PathVariable String container, @PathVariable String slot,
                                     @PathVariable int index, @RequestBody(required = false) FromItem body) {
        FromItem b = body == null ? new FromItem(null, null, null, null) : body;
        return shopping.addFromItem(new ContainerId(container), new SlotId(slot), index, b.store(), b.qty(), b.note(),
            Boolean.TRUE.equals(b.rememberBrand()));
    }

    /** Where to buy another of this item, if its own words say; {@code null} store when they do not. */
    @GetMapping("/c/{container}/{slot}/items/{index}/shopping")
    public Shopping.StoreHint storeFor(@PathVariable String container, @PathVariable String slot, @PathVariable int index) {
        return shopping.hintFor(new ContainerId(container), new SlotId(slot), index)
            .orElse(new Shopping.StoreHint(null, null));
    }

    @GetMapping("/shopping/brands")
    public List<BrandStore> brands() {
        return shopping.brands();
    }

    @PostMapping("/shopping/brands")
    public List<BrandStore> setBrand(@RequestBody BrandStore rule) {
        return shopping.setBrand(rule.brand(), rule.store());
    }

    @DeleteMapping("/shopping/brands")
    public List<BrandStore> forgetBrand(@RequestParam("brand") String brand) {
        return shopping.forgetBrand(brand);
    }

    @GetMapping("/shopping/places")
    public List<ShopPlace> places() {
        return shopping.places();
    }

    public record NewPlace(String store, Double lat, Double lon, Double accuracy) {}

    @PostMapping("/shopping/places")
    public ShopPlace savePlace(@RequestBody NewPlace body) {
        if (body.lat() == null || body.lon() == null) throw new IllegalArgumentException("No position given");
        return shopping.savePlace(body.store(), body.lat(), body.lon(), body.accuracy());
    }

    @DeleteMapping("/shopping/places/{id}")
    public List<ShopPlace> forgetPlace(@PathVariable String id) {
        return shopping.forgetPlace(id);
    }

    /** Any of the text fields, and {@code bought}; absent means unchanged. */
    public record Patch(String what, String store, String partNumber, String details, String qty, Boolean bought) {}

    @PatchMapping("/shopping/{id}")
    public ShoppingEntry update(@PathVariable String id, @RequestBody Patch body) {
        ShoppingEntry e = null;
        if (body.what() != null || body.store() != null || body.partNumber() != null
            || body.details() != null || body.qty() != null) {
            e = shopping.edit(id, new Shopping.Text(body.what(), body.store(), body.partNumber(), body.details(), body.qty()));
        }
        if (body.bought() != null) e = shopping.setBought(id, body.bought());
        if (e == null) throw new IllegalArgumentException("Nothing to change");
        return e;
    }

    @DeleteMapping("/shopping/{id}")
    public void remove(@PathVariable String id) {
        shopping.remove(id);
    }

    @DeleteMapping("/shopping")
    public Map<String, Integer> clearBought(@RequestParam(value = "bought", defaultValue = "false") boolean bought) {
        // Only ever the bought ones: clearing the whole list by one request
        // without a qualifier is a slip waiting to happen.
        if (!bought) throw new IllegalArgumentException("Only ?bought=true is supported");
        return Map.of("removed", shopping.clearBought());
    }

    @GetMapping("/shopping/photos/{name}")
    public ResponseEntity<byte[]> photo(@PathVariable String name,
                                        @RequestParam(name = "w", required = false) Integer width) {
        try {
            byte[] bytes = width == null ? shopping.photo(name) : shopping.thumbnail(name, PhotoController.edge(width));
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(bytes);
        } catch (UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo: " + name, e);
        }
    }
}
