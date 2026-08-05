package family.eilertsen.rack.adapter.out.springai;

/** Shared handling for replies that are meant to be JSON and sometimes aren't quite. */
final class SpringAi {

    private SpringAi() {}

    /** Models wrap JSON in ```json fences often enough to be worth undoing here. */
    static String stripCodeFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.strip();
    }
}
