package family.eilertsen.rack.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every structured call in this app has been handed prose around its JSON at
 * least once. The extractor's version cost a real upload, twice, which is the
 * worst place for it: filing a drawer is the one call where failing means going
 * back for the photograph.
 */
class ModelReplyTest {

    @Test
    void takesAnArrayOutFromBehindAPreamble() {
        // Verbatim from the failure: the extractor answered this mid-filing and
        // the parse died on the letter L.
        String raw = "Looking at the photos:\n[{\"name\":\"BC547\",\"confidence\":0.9}]";

        assertThat(ModelReply.json(raw)).isEqualTo("[{\"name\":\"BC547\",\"confidence\":0.9}]");
    }

    @Test
    void takesAnObjectOutFromBehindAPreamble() {
        assertThat(ModelReply.json("I'll go through what it needs.\n\n{\"summary\":\"ok\"}"))
            .isEqualTo("{\"summary\":\"ok\"}");
    }

    @Test
    void undoesACodeFence() {
        assertThat(ModelReply.json("```json\n[\"tape\"]\n```")).isEqualTo("[\"tape\"]");
        assertThat(ModelReply.json("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void dropsASignOffAfterTheValue() {
        assertThat(ModelReply.json("{\"a\":1}\n\nHope that helps!")).isEqualTo("{\"a\":1}");
    }

    @Test
    void leavesCleanJsonExactlyAsItIs() {
        assertThat(ModelReply.json("[1,2,3]")).isEqualTo("[1,2,3]");
        assertThat(ModelReply.json("{\"a\":[1,{\"b\":2}]}")).isEqualTo("{\"a\":[1,{\"b\":2}]}");
    }

    @Test
    void picksTheOpenerThatActuallyStartsTheValue() {
        // A brace in the prose ahead of an array used to decide the start, which
        // produced "{...]" — valid-looking and unparseable.
        assertThat(ModelReply.json("Looking at {the photos}: [{\"name\":\"x\"}]"))
            .isEqualTo("{the photos}");
        // ...and the reverse: a bracket in the prose ahead of an object.
        assertThat(ModelReply.json("Note [1]: {\"a\":1}")).isEqualTo("[1]");
    }

    @Test
    void isNotFooledByBracesInsideStrings() {
        // A description like "TO-220 {tab up}" would otherwise close the value.
        String raw = "[{\"description\":\"TO-220 {tab up}\",\"note\":\"a ] here too\"}]";

        assertThat(ModelReply.json(raw)).isEqualTo(raw);
    }

    @Test
    void isNotFooledByAnEscapedQuote() {
        String raw = "{\"name\":\"a \\\" brace } inside\",\"b\":1}";

        assertThat(ModelReply.json(raw)).isEqualTo(raw);
    }

    @Test
    void givesTheParserWhatItCanOfATruncatedReply() {
        // Cut off by a token limit. Returning the fragment lets Jackson report
        // "unexpected end of input", which says more than "no JSON found".
        assertThat(ModelReply.json("[{\"name\":\"BC547\"},{\"name\":\"BC5"))
            .startsWith("[{\"name\":\"BC547\"}");
    }

    @Test
    void handsBackWhatItGotWhenThereIsNoJsonAtAll() {
        assertThat(ModelReply.json("I cannot help with that.")).isEqualTo("I cannot help with that.");
        assertThat(ModelReply.json(null)).isEmpty();
        assertThat(ModelReply.json("   ")).isEmpty();
    }
}
