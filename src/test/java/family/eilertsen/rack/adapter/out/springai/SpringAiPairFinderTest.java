package family.eilertsen.rack.adapter.out.springai;

import family.eilertsen.rack.domain.model.Companion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiPairFinderTest {

    @Test
    void keepsWhatTheModelCalledAPairOrTheSameKindAndDropsTheMerelyRelated() {
        // Sonnet cited the ceiling rose beside a USB wall outlet and wrote
        // "merely related, not a pair" in the reason. The verdict is where it
        // gets to say so.
        List<Companion> kept = SpringAiPairFinder.companions(Arrays.asList(
            new SpringAiPairFinder.Cited("S1", "lab/11#0", "pair", "powers it"),
            new SpringAiPairFinder.Cited("S1", "lab/11#10", "same kind", "another 5V USB supply"),
            new SpringAiPairFinder.Cited("S2", "box01/Box1#3", "related", "same wiring installation"),
            new SpringAiPairFinder.Cited("S2", "lab/6#2", " Pair ", "the cable it was made for"),
            new SpringAiPairFinder.Cited("S9", "lab/6#2", "pair", "no such subject"),
            new SpringAiPairFinder.Cited("S1", null, "pair", ""),
            null), 2);

        assertThat(kept).containsExactly(
            new Companion(0, "lab/11#0", Companion.Kind.PAIR, "powers it"),
            new Companion(0, "lab/11#10", Companion.Kind.SAME_KIND, "another 5V USB supply"),
            new Companion(1, "lab/6#2", Companion.Kind.PAIR, "the cable it was made for"));
    }

    @Test
    void aClaimStandsOnlyWhenTheModelSaysSoAndAnUnansweredOneDoesNot() {
        List<Boolean> stands = SpringAiPairFinder.held(Arrays.asList(
            new SpringAiPairFinder.Verdict(1, true, "made for it"),
            new SpringAiPairFinder.Verdict(2, false, "brand in common"),
            new SpringAiPairFinder.Verdict(7, true, "no such claim"),
            new SpringAiPairFinder.Verdict(null, true, ""),
            null), 4);

        assertThat(stands).containsExactly(true, false, false, false);
    }

    @Test
    void aLoneSubjectNeedNotBeNamed() {
        List<Companion> kept = SpringAiPairFinder.companions(List.of(
            new SpringAiPairFinder.Cited(null, "lab/11#0", "pair", "powers it")), 1);

        assertThat(kept).containsExactly(new Companion(0, "lab/11#0", Companion.Kind.PAIR, "powers it"));
    }
}
