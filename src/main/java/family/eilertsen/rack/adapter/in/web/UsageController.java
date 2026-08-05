package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.model.Usage;
import family.eilertsen.rack.domain.port.UsageLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class UsageController {

    private final UsageLog log;

    public UsageController(UsageLog log) {
        this.log = log;
    }

    /** What the rack has spent on model calls since it was built, per model and in total. */
    @GetMapping("/usage")
    public Report usage() {
        Map<String, Usage> byModel = log.byModel();
        Usage total = byModel.values().stream().reduce(Usage.NONE, Usage::plus);
        List<ModelUsage> models = byModel.entrySet().stream()
            .map(e -> new ModelUsage(e.getKey(), e.getValue().calls(),
                e.getValue().inputTokens(), e.getValue().outputTokens()))
            .sorted((a, b) -> Long.compare(b.inputTokens() + b.outputTokens(),
                a.inputTokens() + a.outputTokens()))
            .toList();
        return new Report(total.calls(), total.inputTokens(), total.outputTokens(), models);
    }

    public record ModelUsage(String model, long calls, long inputTokens, long outputTokens) {}

    public record Report(long calls, long inputTokens, long outputTokens, List<ModelUsage> models) {}
}
