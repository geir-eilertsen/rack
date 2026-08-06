package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AskAboutRack;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * One question about the whole rack. A POST because it costs a model call and
 * must not be fired by a link being followed or a page being refreshed.
 */
@RestController
public class AskController {

    private final AskAboutRack ask;

    public AskController(AskAboutRack ask) {
        this.ask = ask;
    }

    @PostMapping("/ask")
    public AskAboutRack.Answer ask(@RequestBody Question body) {
        return ask.execute(body == null ? null : body.question());
    }

    public record Question(String question) {}
}
