package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.FindItems;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final FindItems find;

    public SearchController(FindItems find) {
        this.find = find;
    }

    /**
     * {@code smart=false} (the default) is the literal pass — instant, no model
     * call, safe to fire on every keystroke. The page follows up with
     * {@code smart=true} once typing settles, and that one widens the query when
     * the literal pass came up short.
     */
    @GetMapping("/search")
    public FindItems.Result search(
        @RequestParam String q,
        @RequestParam(defaultValue = "false") boolean smart
    ) {
        return smart ? find.smart(q) : find.literal(q);
    }
}
