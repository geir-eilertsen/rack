package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Extraction;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.port.PartExtractor;
import family.eilertsen.rack.domain.port.UsageLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiPartExtractor implements PartExtractor {

    private static final String PROMPT = """
        You are cataloguing small electronic parts.

        You are given %d photo(s) of the contents of ONE storage slot, numbered 0
        to %d in the order supplied. When there is more than one they are views of
        the same slot — a different angle, or a part in one frame and its printed
        label or bag in another.

        Return a JSON array with one entry per DISTINCT physical item across all
        the photos. A slot usually holds several different things, so list each of
        them. But one thing photographed more than once is a SINGLE entry, not one
        per frame: merge what the frames tell you, so a part number legible only on
        the label shot belongs to the entry for the part it labels.

        Each entry must be a JSON object with exactly these fields:
        - name (string): a short label to scan a list by, at most four words —
          "BC547 transistor", "M4 hex bolts", "Raspberry Pi 4". No sentence, no
          punctuation, no colour or packaging detail; that belongs below.
        - description (string): a fuller line — packaging, markings, condition,
          what distinguishes this from a similar part in the same drawer
        - part_number (string or null): printed markings if legible, otherwise null
        - category (string): one of "ic", "transistor", "resistor", "capacitor", "diode", "connector", "module", "fastener", "cable", "other"
        - qty_estimate (integer): count visible across the photos, counting a thing once even when it shows up in several; 1 if unclear
        - confidence (number between 0 and 1): how sure you are of the identification
        - tags (array of strings): freeform useful tags (e.g. package type like "TO-220" or "SMD 0603")
        - image_indexes (array of integers): EVERY photo that shows this item,
          the clearest one first. One thing shot from two angles with its label
          on a third is one entry listing all three. Do not list a photo that
          does not show this item.

        Return ONLY the JSON array. No prose, no markdown, no code fences.
        """;

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final UsageLog usage;
    private final ChatOptions options;

    public SpringAiPartExtractor(
        ChatClient.Builder builder,
        ObjectMapper mapper,
        UsageLog usage,
        @Value("${rack.ai.extraction-model}") String model,
        @Value("${rack.ai.extraction-max-tokens}") int maxTokens
    ) {
        this.chat = builder.build();
        this.mapper = mapper;
        this.usage = usage;
        // Named here rather than left to the shared default: this is the call
        // whose model choice decides whether the index is true.
        this.options = ChatOptions.builder().model(model).maxTokens(maxTokens).build();
    }

    @Override
    public List<Extraction> extract(List<byte[]> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("at least one image is required");
        }

        Media[] media = images.stream()
            .map(bytes -> Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_JPEG)
                .data(new ByteArrayResource(bytes))
                .build())
            .toArray(Media[]::new);

        String prompt = PROMPT.formatted(images.size(), images.size() - 1);

        ChatResponse response = chat.prompt()
            .options(options)
            .user(u -> u.text(prompt).media(media))
            .call()
            .chatResponse();
        String reply = SpringAi.tally(response, usage);

        String json = SpringAi.stripCodeFences(reply);
        try {
            List<ExtractedItem> extracted = mapper.readValue(json, new TypeReference<>() {});
            return extracted.stream().map(e -> e.toExtraction(images.size())).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Model returned non-JSON reply: " + reply, e);
        }
    }

    private record ExtractedItem(
        String name,
        String description,
        String partNumber,
        String category,
        Integer qtyEstimate,
        double confidence,
        List<String> tags,
        List<Integer> imageIndexes
    ) {
        Extraction toExtraction(int imageCount) {
            Item item = new Item(name, description, partNumber, category, qtyEstimate, confidence, tags, null, null, null);
            return new Extraction(item, inRange(imageIndexes, imageCount));
        }

        /**
         * The model omits, repeats or invents an index often enough to be worth
         * pinning: out-of-range and duplicate frames go, and an entry left with
         * nothing falls back to the first photo rather than to no photo.
         */
        private static List<Integer> inRange(List<Integer> indexes, int imageCount) {
            if (indexes == null) return List.of(0);
            List<Integer> kept = new ArrayList<>();
            for (Integer index : indexes) {
                if (index == null || index < 0 || index >= imageCount) continue;
                if (!kept.contains(index)) kept.add(index);
            }
            return kept.isEmpty() ? List.of(0) : List.copyOf(kept);
        }
    }
}
