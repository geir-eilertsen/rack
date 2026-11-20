package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.KnownBrands;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BrandController {

    private final KnownBrands brands;

    public BrandController(KnownBrands brands) {
        this.brands = brands;
    }

    /** Every brand already on an item or in a brand rule, for a brand box to offer. */
    @GetMapping("/brands")
    public List<String> brands() {
        return brands.all();
    }
}
