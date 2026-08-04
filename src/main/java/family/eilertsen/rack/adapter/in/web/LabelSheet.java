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
import java.util.List;

final class LabelSheet {

    private static final float MM = 2.83464567f;
    private static final float PAGE_W = 210 * MM;
    private static final float PAGE_H = 297 * MM;

    // Sheet paper is always Avery L7160 (A4 21-up, 63.5 × 38.1mm slot)
    private static final int COLS = 3;
    private static final int ROWS = 7;
    private static final float MARGIN_LEFT = 7.2f * MM;
    private static final float MARGIN_TOP = 15.1f * MM;
    private static final float LABEL_H = 38.1f * MM;
    private static final float H_PITCH = 66.0f * MM;
    private static final float V_PITCH = 38.1f * MM;

    // Per-slot content at scale 1.0 (fills the L7160 slot). Multiplied by container.labelScale().
    private static final float QR_SIZE_1X = 30 * MM;
    private static final float PADDING_1X = 2 * MM;
    private static final float FONT_SIZE_1X = 40f;

    private LabelSheet() {}

    static byte[] build(String baseUrl, Container container, List<SlotId> slots) throws IOException {
        int perPage = COLS * ROWS;
        int pageCount = Math.max(1, (slots.size() + perPage - 1) / perPage);

        float scale = container.labelScale() <= 0 ? 1.0f : container.labelScale();
        float qrSize = QR_SIZE_1X * scale;
        float padding = PADDING_1X * scale;
        float fontSize = FONT_SIZE_1X * scale;

        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (int i = 0; i < perPage; i++) {
                        int idx = p * perPage + i;
                        if (idx >= slots.size()) break;

                        SlotId sid = slots.get(idx);
                        int col = i % COLS;
                        int row = i / COLS;
                        float x = MARGIN_LEFT + col * H_PITCH;
                        float y = PAGE_H - MARGIN_TOP - (row + 1) * V_PITCH;

                        drawLabel(doc, cs, font, container, sid, baseUrl, x, y, qrSize, padding, fontSize);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void drawLabel(PDDocument doc, PDPageContentStream cs, PDType1Font font,
                                   Container container, SlotId slot, String baseUrl,
                                   float x, float y, float qrSize, float padding, float fontSize) throws IOException {
        String url = baseUrl + "/put.html?c=" + container.id().value() + "&s=" + slot.value();

        PDImageXObject qr = qrImage(doc, url);
        // Anchor content to the top-left of the L7160 slot so trimming small labels is easy.
        float qrTop = y + LABEL_H - padding;
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
