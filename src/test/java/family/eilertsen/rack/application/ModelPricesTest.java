package family.eilertsen.rack.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModelPricesTest {

    private static final ModelPrices PRICES = new ModelPrices(Map.of(
        "claude-haiku-4-5", new ModelPrices.Price(1.00, 5.00),
        "claude-sonnet-4-6", new ModelPrices.Price(3.00, 15.00),
        "claude-opus-4", new ModelPrices.Price(99.00, 99.00),
        "claude-opus-4-8", new ModelPrices.Price(5.00, 25.00)));

    @Test
    void pricesAMillionTokensAtTheConfiguredRate() {
        assertThat(PRICES.costOf("claude-sonnet-4-6", 1_000_000, 1_000_000))
            .isEqualTo(18.00, within(1e-9));
    }

    @Test
    void pricesTheDatedIdTheApiActuallyReportsBack() {
        // A request for claude-haiku-4-5 is answered by claude-haiku-4-5-20251001,
        // and it is the answer that gets tallied.
        assertThat(PRICES.costOf("claude-haiku-4-5-20251001", 2_000_000, 100_000))
            .isEqualTo(2.5, within(1e-9));
    }

    @Test
    void takesTheLongestMatchingIdRatherThanTheFirst() {
        // "claude-opus-4" must not price claude-opus-4-8 at its own rate.
        assertThat(PRICES.costOf("claude-opus-4-8", 1_000_000, 0)).isEqualTo(5.00, within(1e-9));
    }

    @Test
    void reportsNoPriceRatherThanAFreeCallForAnUnknownModel() {
        assertThat(PRICES.costOf("claude-something-new", 1_000_000, 1_000_000)).isNull();
    }

    @Test
    void survivesNoPriceTableAtAll() {
        assertThat(new ModelPrices(null).costOf("claude-haiku-4-5", 10, 10)).isNull();
    }
}
