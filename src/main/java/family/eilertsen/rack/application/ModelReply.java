package family.eilertsen.rack.application;

/**
 * Getting the JSON out of a reply that was asked for JSON and is nearly that.
 *
 * <p>Every call in this app that wants structured output has been given prose
 * around it at least once. {@code /ask} opened with "I'll go through what a Quad
 * 606 restoration needs…"; the vision extractor, mid-filing, answered "Looking at
 * the photos:" and then the array — and that one took the user's upload down
 * twice, because filing a drawer is the one call where a failure costs a photo
 * you have to go and take again.
 *
 * <p>There were two half-measures before this: one that undid code fences but not
 * a preamble, and one that handled a preamble but only around an object, where
 * the extractor and the expander both answer with an array. Tightening the
 * prompts is a guess about the next reply. Taking the value out of the text is
 * not, so it is done in one place for all of them.
 */
public final class ModelReply {

    private ModelReply() {
    }

    /**
     * The outermost JSON object or array in {@code raw}, or {@code raw} stripped
     * if there is none to find — leaving the parser to give the better error.
     */
    public static String json(String raw) {
        String s = raw == null ? "" : raw.strip();
        int start = firstOpener(s);
        if (start < 0) return s;

        int end = matchingCloser(s, start);
        if (end > start) return s.substring(start, end + 1);

        // Unbalanced — truncated mid-reply, most likely. Fall back to the last
        // closer of the right kind so the parser sees as much as there is.
        char wanted = s.charAt(start) == '{' ? '}' : ']';
        int last = s.lastIndexOf(wanted);
        return last > start ? s.substring(start, last + 1) : s.substring(start);
    }

    /** Whichever of {@code {} or {@code [} comes first, since either may be the value. */
    private static int firstOpener(String s) {
        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        if (brace < 0) return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }

    /**
     * Depth-counted, and blind inside string literals — a brace in a description
     * ("TO-220 {tab up}") would otherwise close the value early.
     */
    private static int matchingCloser(String s, int start) {
        char open = s.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
                continue;
            }
            if (c == open) depth++;
            else if (c == close && --depth == 0) return i;
        }
        return -1;
    }
}
