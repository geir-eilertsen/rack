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
    private static final float LABEL_H = 38.1f * MM;
    private static final float H_PITCH = 66.0f * MM;
    private static final float V_PITCH = 38.1f * MM;

    // Per-slot content at scale 1.0 (fills the L7160 slot). Multiplied by container.labelScale().
    private static final float QR_SIZE_1X = 30 * MM;
    private static final float PADDING_1X = 2 * MM;
    private static final float FONT_SIZE_1X = 40f;

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

    /**
     * Greedily stacks consecutive labels into one physical L7160 label while they still fit, so small-scale
     * containers stop wasting most of each sticker — two 0.4-scale labels share one, to be trimmed apart.
     * Mixed scales pack fine because each label is measured individually.
     */
    static List<List<Label>> pack(List<Label> labels) {
        List<List<Label>> positions = new ArrayList<>();
        List<Label> current = null;
        float used = 0;
        for (Label label : labels) {
            float h = contentHeight(label);
            if (current == null || used + h > LABEL_H) {
                current = new ArrayList<>();
                positions.add(current);
                used = 0;
            }
            current.add(label);
            used += h;
        }
        return positions;
    }

    /** Physical labels (not logical ones) a run consumes — what the sheet offset has to be counted in. */
    static int positionCount(List<Label> labels) {
        return pack(labels).size();
    }

    static byte[] build(String baseUrl, List<Label> labels, int firstPageOffset) throws IOException {
        int offset = Math.max(0, firstPageOffset % PER_SHEET);
        List<List<Label>> positions = pack(labels);
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

                        // Stack the position's labels downwards from the top of the L7160 slot.
                        float top = y + LABEL_H;
                        for (Label label : positions.get(posIdx++)) {
                            float scale = scaleOf(label);
                            drawLabel(doc, cs, font, label.container(), label.slot(), baseUrl, x, top,
                                QR_SIZE_1X * scale, PADDING_1X * scale, FONT_SIZE_1X * scale);
                            top -= contentHeight(label);
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
                                   Container container, SlotId slot, String baseUrl,
                                   float x, float top, float qrSize, float padding, float fontSize) throws IOException {
        String url = baseUrl + "/put.html?c=" + container.id().value() + "&s=" + slot.value();

        PDImageXObject qr = qrImage(doc, url);
        // Anchor content to the top-left of the band so trimming small labels is easy.
        float qrTop = top - padding;
        cs.drawImage(qr, x + padding, qrTop - qrSize, qrSize, qrSize);

        float textX = x + padding + qrSize + padding;
        float textY = qrTop - qrSize / 2 - fontSize / 3;
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(textX, textY);
        cs.showText(slot.value());
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
