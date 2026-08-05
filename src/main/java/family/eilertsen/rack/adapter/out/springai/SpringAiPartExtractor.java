package family.eilertsen.rack.adapter.out.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.model.Extraction;
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
        - image_index (integer): index of the photo that shows this item best

        Return ONLY the JSON array. No prose, no markdown, no code fences.
        """;

    private final ChatClient chat;
    private final ObjectMapper mapper;

    public SpringAiPartExtractor(ChatClient.Builder builder, ObjectMapper mapper) {
        this.chat = builder.build();
        this.mapper = mapper;
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

        String reply = chat.prompt()
            .user(u -> u.text(prompt).media(media))
            .call()
            .content();

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
        Integer imageIndex
    ) {
        Extraction toExtraction(int imageCount) {
            Item item = new Item(name, description, partNumber, category, qtyEstimate, confidence, tags, null, null, null);
            return new Extraction(item, inRange(imageIndex, imageCount));
        }

        /** The model omits or invents an index often enough to be worth pinning to the first photo. */
        private static int inRange(Integer index, int imageCount) {
            return index == null || index < 0 || index >= imageCount ? 0 : index;
        }
    }
}
