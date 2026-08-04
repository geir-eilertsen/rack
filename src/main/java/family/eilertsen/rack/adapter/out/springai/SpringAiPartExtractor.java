package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Item;
import family.eilertsen.rack.domain.port.PartExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.util.List;

@Component
public class SpringAiPartExtractor implements PartExtractor {

    private static final String PROMPT = """
        You are cataloguing small electronic parts.

        Look at the image and return a JSON array of items you can identify.
        If the image shows one thing, return an array with one entry.
        If the image shows multiple distinct parts, return one entry per part.

        Each entry must be a JSON object with exactly these fields:
        - description (string): a short human-readable description
        - part_number (string or null): printed markings if legible, otherwise null
        - category (string): one of "ic", "transistor", "resistor", "capacitor", "diode", "connector", "module", "fastener", "cable", "other"
        - qty_estimate (integer): count visible in frame; 1 if unclear
        - confidence (number between 0 and 1): how sure you are of the identification
        - tags (array of strings): freeform useful tags (e.g. package type like "TO-220" or "SMD 0603")

        Return ONLY the JSON array. No prose, no markdown, no code fences.
        """;

    private final ChatClient chat;
    private final ObjectMapper mapper;

    public SpringAiPartExtractor(ChatClient.Builder builder, ObjectMapper mapper) {
        this.chat = builder.build();
        this.mapper = mapper;
    }

    @Override
    public List<Item> extract(byte[] image) {
        Media media = Media.builder()
            .mimeType(MimeTypeUtils.IMAGE_JPEG)
            .data(new ByteArrayResource(image))
            .build();

        String reply = chat.prompt()
            .user(u -> u.text(PROMPT).media(media))
            .call()
            .content();

        String json = stripCodeFences(reply);
        try {
            List<ExtractedItem> extracted = mapper.readValue(json, new TypeReference<>() {});
            return extracted.stream().map(ExtractedItem::toItem).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Model returned non-JSON reply: " + reply, e);
        }
    }

    private static String stripCodeFences(String s) {
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

    private record ExtractedItem(
        String description,
        String partNumber,
        String category,
        Integer qtyEstimate,
        double confidence,
        List<String> tags
    ) {
        Item toItem() {
            return new Item(description, partNumber, category, qtyEstimate, confidence, tags, null);
        }
    }
}
