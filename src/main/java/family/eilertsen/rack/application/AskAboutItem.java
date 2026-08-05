package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.model.Slot;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.port.PartIndex;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AskAboutItem {

    private static final String SYSTEM = """
        You are helping the user identify or reason about a small stored item
        (electronic part, fastener, connector, etc.). Answer briefly and
        directly, no preamble. If the given item context does not tell you
        enough to answer with confidence, say so.
        """;

    private final PartIndex index;
    private final ChatClient chat;
    private final ChatOptions options;

    public AskAboutItem(
        PartIndex index,
        ChatClient.Builder builder,
        @Value("${rack.ai.ask-model}") String model,
        @Value("${rack.ai.ask-max-tokens}") int maxTokens
    ) {
        this.index = index;
        this.chat = builder.build();
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    public Slot execute(ContainerId container, SlotId slotId, int itemIndex, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        Slot existing = index.get(container, slotId)
            .orElseThrow(() -> new NoSuchElementException("Slot has no items: " + container.value() + "/" + slotId.value()));

        if (itemIndex < 0 || itemIndex >= existing.items().size()) {
            throw new IndexOutOfBoundsException(
                "Item index " + itemIndex + " out of range (0.." + (existing.items().size() - 1) + ")");
        }

        Item current = existing.items().get(itemIndex);
        String prompt = buildPrompt(current, question.trim());
        String answer = chat.prompt().options(options).system(SYSTEM).user(prompt).call().content().strip();

        List<Item.QA> qa = new ArrayList<>(current.qa() == null ? List.of() : current.qa());
        qa.add(new Item.QA(question.trim(), answer, Instant.now()));

        Item updated = new Item(
            current.name(),
            current.description(),
            current.partNumber(),
            current.category(),
            current.qtyEstimate(),
            current.confidence(),
            current.tags(),
            current.embedding(),
            List.copyOf(qa),
            current.sourcePhoto()
        );

        List<Item> newItems = new ArrayList<>(existing.items());
        newItems.set(itemIndex, updated);
        Slot saved = new Slot(existing.id(), List.copyOf(newItems), existing.lastVerified(),
            existing.photos(), existing.printedAt());
        index.save(container, saved);
        return saved;
    }

    private static String buildPrompt(Item item, String question) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("Item context:\n");
        ctx.append("- Name: ").append(orUnknown(item.name())).append('\n');
        ctx.append("- Description: ").append(orUnknown(item.description())).append('\n');
        ctx.append("- Part number: ").append(orUnknown(item.partNumber())).append('\n');
        ctx.append("- Category: ").append(orUnknown(item.category())).append('\n');
        ctx.append("- Quantity: ").append(item.qtyEstimate() == null ? "unknown" : item.qtyEstimate()).append('\n');
        ctx.append("- Tags: ").append(item.tags() == null || item.tags().isEmpty() ? "none" : String.join(", ", item.tags())).append("\n\n");
        ctx.append("User question: ").append(question);
        return ctx.toString();
    }

    private static String orUnknown(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
