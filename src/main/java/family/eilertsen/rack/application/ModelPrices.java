package family.eilertsen.rack.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * What each model costs per million tokens, from config rather than code —
 * published prices change, and a number on screen that quietly went stale is
 * worse than no number.
 *
 * <p>Lookup tolerates a dated model id: the API answers a request for
 * {@code claude-haiku-4-5} with {@code claude-haiku-4-5-20251001}, and it is the
 * answer that gets tallied. The longest configured id that the reported one
 * starts with wins, so {@code claude-opus-4-8} is never priced by an entry for
 * {@code claude-opus-4}.
 */
@ConfigurationProperties("rack.ai")
public record ModelPrices(Map<String, Price> prices) {

    /** Dollars per million tokens. */
    public record Price(double input, double output) {}

    /** Null when the model has no configured price — an unpriced call is not a free one. */
    public Double costOf(String model, long inputTokens, long outputTokens) {
        Price price = priceOf(model);
        if (price == null) return null;
        return (inputTokens * price.input() + outputTokens * price.output()) / 1_000_000d;
    }

    private Price priceOf(String model) {
        if (model == null || prices == null) return null;
        Price best = null;
        int longest = -1;
        for (Map.Entry<String, Price> entry : prices.entrySet()) {
            String id = entry.getKey();
            boolean matches = model.equals(id) || model.startsWith(id + "-");
            if (matches && id.length() > longest) {
                longest = id.length();
                best = entry.getValue();
            }
        }
        return best;
    }
}
