package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.ModelPrices;
import family.eilertsen.rack.domain.model.Usage;
import family.eilertsen.rack.domain.port.UsageLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class UsageController {

    private final UsageLog log;
    private final ModelPrices prices;

    public UsageController(UsageLog log, ModelPrices prices) {
        this.log = log;
        this.prices = prices;
    }

    /** What the rack has spent on model calls since it was built, per model and in total. */
    @GetMapping("/usage")
    public Report usage() {
        List<ModelUsage> models = new ArrayList<>();
        Usage total = Usage.NONE;
        double cost = 0;
        boolean everythingPriced = true;

        for (Map.Entry<String, Usage> entry : log.byModel().entrySet()) {
            Usage u = entry.getValue();
            Double modelCost = prices.costOf(entry.getKey(), u.inputTokens(), u.outputTokens());
            models.add(new ModelUsage(entry.getKey(), u.calls(), u.inputTokens(), u.outputTokens(), modelCost));
            total = total.plus(u);
            if (modelCost == null) everythingPriced = false;
            else cost += modelCost;
        }

        models.sort((a, b) -> Long.compare(b.inputTokens() + b.outputTokens(),
            a.inputTokens() + a.outputTokens()));

        // A model with no configured price is not a free one, so say the total is
        // short rather than presenting it as the whole bill.
        return new Report(total.calls(), total.inputTokens(), total.outputTokens(), cost, everythingPriced, models);
    }

    public record ModelUsage(String model, long calls, long inputTokens, long outputTokens, Double cost) {}

    public record Report(
        long calls,
        long inputTokens,
        long outputTokens,
        double cost,
        boolean allPriced,
        List<ModelUsage> models
    ) {}
}
