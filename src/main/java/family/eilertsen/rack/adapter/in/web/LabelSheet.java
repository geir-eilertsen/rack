package family.eilertsen.rack.adapter.in.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import family.eilertsen.rack.domain.model.DrawerId;
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

    // Avery L7160: A4 3×7 = 21 labels/page, label 63.5 × 38.1 mm
    private static final int COLS = 3;
    private static final int ROWS = 7;
    private static final float MARGIN_LEFT = 7.2f * MM;
    private static final float MARGIN_TOP = 15.1f * MM;
    private static final float LABEL_W = 63.5f * MM;
    private static final float LABEL_H = 38.1f * MM;
    private static final float H_PITCH = 66.0f * MM;   // 63.5 + 2.5 gap
    private static final float V_PITCH = 38.1f * MM;   // no vertical gap

    private static final float QR_SIZE = 30 * MM;
    private static final float PADDING = 2 * MM;
    private static final float ID_FONT_SIZE = 40f;

    private LabelSheet() {}

    static byte[] build(String baseUrl) throws IOException {
        List<DrawerId> ids = DrawerId.all();
        int perPage = COLS * ROWS;
        int pageCount = (ids.size() + perPage - 1) / perPage;

        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (int i = 0; i < perPage; i++) {
                        int idx = p * perPage + i;
                        if (idx >= ids.size()) break;

                        DrawerId id = ids.get(idx);
                        int col = i % COLS;
                        int row = i / COLS;
                        float x = MARGIN_LEFT + col * H_PITCH;
                        float y = PAGE_H - MARGIN_TOP - (row + 1) * V_PITCH;

                        drawLabel(doc, cs, font, id, baseUrl, x, y);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void drawLabel(PDDocument doc, PDPageContentStream cs, PDType1Font font,
                                   DrawerId id, String baseUrl, float x, float y) throws IOException {
        String url = baseUrl + "/d/" + id.value();

        PDImageXObject qr = qrImage(doc, url);
        float qrY = y + (LABEL_H - QR_SIZE) / 2;
        cs.drawImage(qr, x + PADDING, qrY, QR_SIZE, QR_SIZE);

        float textX = x + PADDING + QR_SIZE + PADDING;
        float textY = y + LABEL_H / 2 - ID_FONT_SIZE / 3;
        cs.beginText();
        cs.setFont(font, ID_FONT_SIZE);
        cs.newLineAtOffset(textX, textY);
        cs.showText(id.value());
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
