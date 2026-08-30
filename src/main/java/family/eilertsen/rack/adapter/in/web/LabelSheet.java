package family.eilertsen.rack.adapter.in.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.SlotId;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class LabelSheet {

    private static final float MM = 2.83464567f;
    private static final float PAGE_W = 210 * MM;
    private static final float PAGE_H = 297 * MM;

    // Sheet paper is always Avery L7160 (A4 21-up, 63.5 × 38.1mm slot)
    private static final int COLS = 3;
    private static final int ROWS = 7;
    static final int PER_SHEET = COLS * ROWS;
    private static final float MARGIN_LEFT = 7.2f * MM;
    private static final float MARGIN_TOP = 15.1f * MM;
    private static final float LABEL_W = 63.5f * MM;
    private static final float LABEL_H = 38.1f * MM;
    private static final float H_PITCH = 66.0f * MM;
    private static final float V_PITCH = 38.1f * MM;

    // Per-slot content at scale 1.0 (fills the L7160 slot). Multiplied by container.labelScale().
    private static final float QR_SIZE_1X = 30 * MM;
    private static final float PADDING_1X = 2 * MM;
    private static final float FONT_SIZE_1X = 40f;

    // Type set beside the QR. Lines are spaced at 1.15em, which is tight enough that
    // two of them still read as one label rather than as two.
    private static final float LINE_SPACING = 1.15f;
    // Helvetica-Bold caps are 0.718em. Centring the block on its cap height rather
    // than its em box is what puts a single line where a single line always sat.
    private static final float CAP_HEIGHT = 0.72f;
    // Width is estimated rather than measured, at 0.75em a character: Helvetica-Bold
    // caps top out at 0.722em, so the estimate errs wide, and erring wide costs a
    // column rather than causing an overlap.
    private static final float EM_PER_CHAR = 0.75f;
    private static final float MIN_FONT_SIZE = 5f;

    /** One label position on the sheet. Carrying the container per-label lets a single sheet span containers. */
    record Label(Container container, SlotId slot) {}

    private LabelSheet() {}

    static byte[] build(String baseUrl, Container container, List<SlotId> slots, int firstPageOffset) throws IOException {
        return build(baseUrl, slots.stream().map(sid -> new Label(container, sid)).toList(), firstPageOffset);
    }

    private static float scaleOf(Label label) {
        float scale = label.container().labelScale() <= 0 ? 1.0f : label.container().labelScale();
        return scale;
    }

    /** Vertical room one label's content needs. At scale 1.0 this all but fills the L7160 slot; at 0.4 it uses a third. */
    private static float contentHeight(Label label) {
        float scale = scaleOf(label);
        return QR_SIZE_1X * scale + 2 * PADDING_1X * scale;
    }

    /** Horizontal room one label needs: QR, then its words beside it, wrapped as they will be drawn. */
    private static float contentWidth(Label label) {
        float scale = scaleOf(label);
        float padding = PADDING_1X * scale;
        Type type = layout(label);
        return padding + QR_SIZE_1X * scale + padding + widest(type.lines(), type.size()) + padding;
    }

    /**
     * What the sticker says.
     *
     * <p>A container with a single slot has no subdivisions at all, so its label is the name of
     * the box — "Garasje box 1" rather than "1", which is the name of a compartment it has not
     * got and told you nothing standing in front of it. A divided container is labelled by the
     * slot, because that is the part you are looking for once you are there.
     */
    static String text(Label label) {
        Container container = label.container();
        if (container.slots().size() > 1) return label.slot().value();
        return container.name() == null || container.name().isBlank()
            ? label.slot().value()
            : container.name();
    }

    /** The type on one label: the words as they are drawn, and the size they are drawn at. */
    private record Type(List<String> lines, float size) {}

    /** What is left across the sticker once the QR and the padding around it have taken theirs. */
    private static float textRoom(Label label) {
        float scale = scaleOf(label);
        float padding = PADDING_1X * scale;
        return LABEL_W - (padding + QR_SIZE_1X * scale + padding + padding);
    }

    /**
     * Sets the words beside the QR, shrinking and wrapping them until they fit.
     *
     * <p>At scale 1.0 a 30mm QR and 40pt type leave room for two characters on a 63.5mm sticker;
     * "E12" wants 67.8mm and "Box1" 78.3mm, and both used to be drawn anyway, running off the
     * edge. The QR is the part that has to work — its module size is already the floor on how
     * small a label can go — so it keeps its size and the type gives way.
     *
     * <p>Names wrap where ids cannot: "Kjellerbod box 1" on one line sets at 6pt beside a full
     * QR, and over two lines at 10. So the size is stepped down until a wrapping fits both the
     * width and the QR's height, and then solved exactly for that wrapping — the step decides
     * where the lines break, the arithmetic decides how big the type is.
     */
    private static Type layout(Label label) {
        float scale = scaleOf(label);
        float wanted = FONT_SIZE_1X * scale;
        float room = textRoom(label);
        float qr = QR_SIZE_1X * scale;
        String text = text(label);
        if (text.isBlank()) return new Type(List.of(""), wanted);

        for (float size = wanted; size >= MIN_FONT_SIZE; size -= 0.5f) {
            List<String> lines = wrap(text, size, room);
            float widest = widest(lines, size);
            float height = blockHeight(lines.size(), size);
            if (widest <= 0) return new Type(lines, wanted);
            if (widest > room || height > qr) continue;
            float exact = Math.min(size * room / widest, size * qr / height);
            // A hair under, so float arithmetic cannot land a fitted label a
            // rounding error over the edge it was fitted inside.
            return new Type(lines, Math.min(wanted, exact * 0.999f));
        }
        // One word with nowhere to break it — a long slot id — gives way as it always has.
        float em = text.length() * EM_PER_CHAR;
        return new Type(List.of(text), Math.max(room / em * 0.999f, 0f));
    }

    /** Greedy wrap: words go on the current line while they fit, and a word too wide gets a line to itself. */
    private static List<String> wrap(String text, float size, float room) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && textWidth(candidate, size) > room) {
                lines.add(line);
                line = word;
            } else {
                line = candidate;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private static float textWidth(String text, float size) {
        return text.length() * EM_PER_CHAR * size;
    }

    private static float widest(List<String> lines, float size) {
        float widest = 0;
        for (String line : lines) widest = Math.max(widest, textWidth(line, size));
        return widest;
    }

    /** Cap height of the first line plus a line's spacing for each one after it. */
    private static float blockHeight(int lines, float size) {
        return (lines - 1) * size * LINE_SPACING + size * CAP_HEIGHT;
    }

    /** One label's spot inside a sticker, as offsets right and down from the sticker's top-left corner. */
    record Placed(Label label, float dx, float dy) {}

    // Exposed so tests can assert what a sticker says and how big it is set.
    static List<String> linesOf(Label label) {
        return layout(label).lines();
    }

    static float fontSizeOf(Label label) {
        return layout(label).size();
    }

    // Exposed so tests can assert the packing never overlaps or overflows a sticker.
    static float widthOf(Label label) {
        return contentWidth(label);
    }

    static float heightOf(Label label) {
        return contentHeight(label);
    }

    static float stickerWidth() {
        return LABEL_W;
    }

    static float stickerHeight() {
        return LABEL_H;
    }

    /**
     * Shelf-packs consecutive labels into one physical L7160 label, filling across before dropping to the next
     * row, so small-scale containers stop wasting the sticker's width as well as its height — four 0.4-scale
     * labels share one sticker as a 2×2 grid, to be trimmed apart. Mixed scales pack because each label is
     * measured individually; a label that cannot follow the previous one simply starts the next row or sticker.
     */
    static List<List<Placed>> pack(List<Label> labels) {
        List<List<Placed>> stickers = new ArrayList<>();
        List<Placed> current = new ArrayList<>();
        float cursorX = 0;
        float rowTop = 0;
        float rowHeight = 0;

        for (Label label : labels) {
            float w = contentWidth(label);
            float h = contentHeight(label);

            if (!current.isEmpty() && cursorX + w > LABEL_W) {
                rowTop += rowHeight;
                cursorX = 0;
                rowHeight = 0;
            }
            if (!current.isEmpty() && rowTop + h > LABEL_H) {
                stickers.add(current);
                current = new ArrayList<>();
                cursorX = 0;
                rowTop = 0;
                rowHeight = 0;
            }

            current.add(new Placed(label, cursorX, rowTop));
            cursorX += w;
            rowHeight = Math.max(rowHeight, h);
        }
        if (!current.isEmpty()) stickers.add(current);
        return stickers;
    }

    /** Physical labels (not logical ones) a run consumes — what the sheet offset has to be counted in. */
    static int positionCount(List<Label> labels) {
        return pack(labels).size();
    }

    static byte[] build(String baseUrl, List<Label> labels, int firstPageOffset) throws IOException {
        int offset = Math.max(0, firstPageOffset % PER_SHEET);
        List<List<Placed>> positions = pack(labels);
        int totalPositions = offset + positions.size();
        int pageCount = Math.max(1, (totalPositions + PER_SHEET - 1) / PER_SHEET);

        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            int posIdx = 0;
            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    int startPos = (p == 0) ? offset : 0;
                    for (int i = startPos; i < PER_SHEET; i++) {
                        if (posIdx >= positions.size()) break;

                        int col = i % COLS;
                        int row = i / COLS;
                        float x = MARGIN_LEFT + col * H_PITCH;
                        float y = PAGE_H - MARGIN_TOP - (row + 1) * V_PITCH;

                        // Offsets run right and down from the sticker's top-left corner.
                        for (Placed placed : positions.get(posIdx++)) {
                            float scale = scaleOf(placed.label());
                            drawLabel(doc, cs, font, placed.label(), baseUrl,
                                x + placed.dx(), y + LABEL_H - placed.dy(),
                                QR_SIZE_1X * scale, PADDING_1X * scale);
                        }
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** {@code top} is the upper edge of this label's band within the L7160 slot, not the slot's own baseline. */
    private static void drawLabel(PDDocument doc, PDPageContentStream cs, PDType1Font font,
                                   Label label, String baseUrl,
                                   float x, float top, float qrSize, float padding) throws IOException {
        String url = baseUrl + "/put.html?c=" + label.container().id().value() + "&s=" + label.slot().value();

        PDImageXObject qr = qrImage(doc, url);
        // Anchor content to the top-left of the band so trimming small labels is easy.
        float qrTop = top - padding;
        cs.drawImage(qr, x + padding, qrTop - qrSize, qrSize, qrSize);

        Type type = layout(label);
        float textX = x + padding + qrSize + padding;
        // The block is centred on the QR's middle, so one line sits where one line
        // always sat and two straddle it evenly.
        float blockTop = qrTop - qrSize / 2 + blockHeight(type.lines().size(), type.size()) / 2;
        cs.beginText();
        cs.setFont(font, type.size());
        cs.newLineAtOffset(textX, blockTop - type.size() * CAP_HEIGHT);
        for (int i = 0; i < type.lines().size(); i++) {
            if (i > 0) cs.newLineAtOffset(0, -type.size() * LINE_SPACING);
            cs.showText(type.lines().get(i));
        }
        cs.endText();
    }

    private static PDImageXObject qrImage(PDDocument doc, String content) throws IOException {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 400, 400);
            BufferedImage img = MatrixToImageWriter.toBufferedImage(matrix);
            return LosslessFactory.createFromImage(doc, img);
        } catch (WriterException e) {
            throw new IOException("QR encoding failed for: " + content, e);
        }
    }
}
