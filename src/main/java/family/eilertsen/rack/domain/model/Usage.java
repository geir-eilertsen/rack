package family.eilertsen.rack.domain.model;

/** What one model has cost so far, in the only unit the API bills in. */
public record Usage(long calls, long inputTokens, long outputTokens) {

    public static final Usage NONE = new Usage(0, 0, 0);

    public Usage plus(long inputTokens, long outputTokens) {
        return new Usage(calls + 1, this.inputTokens + inputTokens, this.outputTokens + outputTokens);
    }

    public Usage plus(Usage other) {
        return new Usage(calls + other.calls, inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
