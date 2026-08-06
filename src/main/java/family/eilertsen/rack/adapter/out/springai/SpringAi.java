package family.eilertsen.rack.adapter.out.springai;

import family.eilertsen.rack.application.ModelReply;
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

    /**
     * The JSON out of a reply that is nearly JSON.
     *
     * <p>This used to undo code fences and nothing else, which was not enough:
     * mid-filing the extractor answered "Looking at the photos:" ahead of the
     * array and took the upload down with it. {@link ModelReply} does the whole
     * job, for every call that asks for structured output.
     */
    static String json(String s) {
        return ModelReply.json(s);
    }
}
