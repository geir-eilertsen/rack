package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AskAboutRack;
import family.eilertsen.rack.application.DiscussBuild;
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
    private final DiscussBuild discuss;

    public AskController(AskAboutRack ask, PlanPurchases plan, DiscussBuild discuss) {
        this.ask = ask;
        this.plan = plan;
        this.discuss = discuss;
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

    /**
     * A build talked through against the shelf. The whole conversation comes
     * with each message, because nothing is kept on the server: a discussion
     * is a proposal under review, and lives in the browser until something
     * comes of it.
     */
    @PostMapping("/discuss")
    public DiscussBuild.Answer discuss(@RequestBody Conversation body) {
        return discuss.execute(body == null ? null : body.messages());
    }

    public record Question(String question) {}

    public record Conversation(java.util.List<DiscussBuild.Turn> messages) {}

    /** {@code needed} is what the checklist could not find — the things to buy. */
    public record PlanRequest(String project, java.util.List<String> needed) {}
}
