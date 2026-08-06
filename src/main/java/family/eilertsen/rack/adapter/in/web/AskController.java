package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AskAboutRack;
import family.eilertsen.rack.application.PlanPurchases;
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
    private final PlanPurchases plan;

    public AskController(AskAboutRack ask, PlanPurchases plan) {
        this.ask = ask;
        this.plan = plan;
    }

    @PostMapping("/ask")
    public AskAboutRack.Answer ask(@RequestBody Question body) {
        return ask.execute(body == null ? null : body.question());
    }

    /**
     * The second half of the errand: what to buy, from whom, and how to do the
     * job. Separate from {@code /ask} because finding out you have everything is
     * a complete answer on its own, and should not pay for a plan nobody wanted.
     */
    @PostMapping("/plan")
    public PlanPurchases.Plan plan(@RequestBody PlanRequest body) {
        return plan.execute(body == null ? null : body.project(),
            body == null ? null : body.needed());
    }

    public record Question(String question) {}

    /** {@code needed} is what the checklist could not find — the things to buy. */
    public record PlanRequest(String project, java.util.List<String> needed) {}
}
