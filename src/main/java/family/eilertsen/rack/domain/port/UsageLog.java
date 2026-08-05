package family.eilertsen.rack.domain.port;

import family.eilertsen.rack.domain.model.Usage;

import java.util.Map;

/**
 * A running tally of what the model calls have cost, kept per model because the
 * models are priced an order of magnitude apart and the point of the tally is to
 * see which call is doing the spending.
 */
public interface UsageLog {

    /** Never throws: a tally that can break the call it is counting is worse than no tally. */
    void record(String model, long inputTokens, long outputTokens);

    /** Totals per model, since the tally started. */
    Map<String, Usage> byModel();
}
