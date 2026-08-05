package family.eilertsen.rack.adapter.out.springai;

import family.eilertsen.rack.domain.port.UsageLog;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Shared handling for replies that are meant to be JSON and sometimes aren't quite. */
final class SpringAi {

    private SpringAi() {}

    /**
     * Take the reply and count what it cost on the way past. The tally is read
     * from the response rather than assumed from config, so it stays honest when
     * a model is overridden by env var.
     */
    static String tally(ChatResponse response, UsageLog log) {
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage != null) {
            log.record(metadata.getModel(), tokens(usage.getPromptTokens()), tokens(usage.getCompletionTokens()));
        }
        return response.getResult().getOutput().getText();
    }

    private static long tokens(Integer count) {
        return count == null ? 0 : count;
    }

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
