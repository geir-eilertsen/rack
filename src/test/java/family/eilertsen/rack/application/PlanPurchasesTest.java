package family.eilertsen.rack.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supplier choice is the model's judgement and not testable here. What is
 * testable is the honesty rule around it: an order code goes straight into a
 * supplier's search box, so one the inventory cannot vouch for wastes a trip in
 * a way a wrong search term does not.
 */
class PlanPurchasesTest {

    private static final String INVENTORY = String.join("\n",
        "rack/B12 | 220uF 100V capacitor | radial electrolytic Panasonic RoHS Farnell 876-7670 | | capacitor | 4 | ",
        "box01/Box1 | Clas Ohlson mains splitter | Labelled Clas Ohlson 32-7965 GES-011 | | other | 1 | ",
        "lab/2 | Heat sink compound | Dow Corning 340, 150g tube | | other | 1 | ")
        .toLowerCase();

    @Test
    void keepsAnOrderCodeTheInventoryCarries() {
        // A fact about a thing on the shelf: this capacitor's own description
        // records the code it was bought under.
        PlanPurchases.Line line = new PlanPurchases.Line(
            "220uF 100V radial", "8", "220uF 100V radial electrolytic", "876-7670", null);

        assertThat(PlanPurchases.vouched(line, INVENTORY).code()).isEqualTo("876-7670");
    }

    @Test
    void dropsAnOrderCodeNothingInTheInventorySupports() {
        // Plausible, well-formed, and not from anywhere. Typed into Farnell it
        // returns the wrong part or nothing, and the search term was the useful
        // half of the line anyway.
        PlanPurchases.Line line = new PlanPurchases.Line(
            "MJ15024 output transistor", "4", "MJ15024", "241-9976", "match to MJ15025");

        PlanPurchases.Line checked = PlanPurchases.vouched(line, INVENTORY);

        assertThat(checked.code()).isNull();
        // Everything else survives — the line is still worth having.
        assertThat(checked.part()).isEqualTo("MJ15024 output transistor");
        assertThat(checked.search()).isEqualTo("MJ15024");
        assertThat(checked.note()).isEqualTo("match to MJ15025");
        assertThat(checked.qty()).isEqualTo("4");
    }

    @Test
    void aCodeTooShortToBeEvidenceIsDropped() {
        // A listing of 152 items contains every short string, so matching one
        // would vouch for anything at all.
        PlanPurchases.Line line = new PlanPurchases.Line("Zener 5V6", "5", "BZX55C5V6", "340", null);

        assertThat(PlanPurchases.vouched(line, INVENTORY).code()).isNull();
    }

    @Test
    void aCodeMatchesRegardlessOfHowItIsCased() {
        PlanPurchases.Line line = new PlanPurchases.Line(
            "Mains splitter", "1", "4-outlet Schuko splitter", "GES-011", null);

        assertThat(PlanPurchases.vouched(line, INVENTORY).code()).isEqualTo("GES-011");
    }

    @Test
    void aLineWithNoCodeIsLeftExactlyAsItIs() {
        PlanPurchases.Line line = new PlanPurchases.Line("NTC thermistor", "1", "47R NTC", null, null);

        assertThat(PlanPurchases.vouched(line, INVENTORY)).isSameAs(line);
        assertThat(PlanPurchases.vouched(
            new PlanPurchases.Line("x", "1", "x", "   ", null), INVENTORY).code()).isEqualTo("   ");
    }
}
