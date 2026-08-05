package family.eilertsen.rack.adapter.out.springai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model's terms go straight into the index, so what it is allowed to return
 * is worth pinning: it varies between calls, and one loose term undoes the rule
 * that every word of a query has to match.
 */
class SpringAiQueryExpanderTest {

    @Test
    void dropsATermBuiltOnlyFromWordsTheQueryAlreadyHad() {
        // "tape" is not a widening of "isolating tape" — the literal pass
        // searched that word and required "isolating" alongside it. Searching it
        // alone drops the word that made the query specific and brings back the
        // twenty-two resistors that come on tape reels.
        List<String> terms = SpringAiQueryExpander.clean(
            Arrays.asList("electrical tape", "tape", "isolating", "black tape"), "isolating tape");

        assertThat(terms).containsExactly("electrical tape", "black tape");
    }

    @Test
    void keepsANearMissThatIsNotActuallyTheQuerysOwnWord() {
        // "insulating" is one letter from "isolating" and a different word — the
        // rule is about words the search already tried, not words that look alike.
        assertThat(SpringAiQueryExpander.clean(List.of("insulating"), "isolating tape"))
            .containsExactly("insulating");
    }

    @Test
    void keepsATermThatBringsAWordOfItsOwn() {
        List<String> terms = SpringAiQueryExpander.clean(
            Arrays.asList("heat shrink", "shrink tubing"), "krympestrømpe");

        assertThat(terms).containsExactly("heat shrink", "shrink tubing");
    }

    @Test
    void dropsTheQueryEchoedBackAndAnyDuplicate() {
        List<String> terms = SpringAiQueryExpander.clean(
            Arrays.asList("Isolating Tape", "electrical tape", "Electrical Tape"), "isolating tape");

        assertThat(terms).containsExactly("electrical tape");
    }

    @Test
    void survivesTheRaggedEdgesOfAModelReply() {
        List<String> terms = SpringAiQueryExpander.clean(
            Arrays.asList(null, "  ", "a", "  electrical tape  "), "isolating tape");

        assertThat(terms).containsExactly("electrical tape");
    }

    @Test
    void capsHowManyTermsAQueryCanFanOutInto() {
        List<String> terms = SpringAiQueryExpander.clean(
            Arrays.asList("one x", "two x", "three x", "four x", "five x", "six x", "seven x"), "tape");

        assertThat(terms).hasSize(6);
    }
}
