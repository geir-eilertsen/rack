package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.model.SearchHit;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SearchController {

    private final PartIndex index;

    public SearchController(PartIndex index) {
        this.index = index;
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q) {
        return index.searchByKeyword(q);
    }
}
