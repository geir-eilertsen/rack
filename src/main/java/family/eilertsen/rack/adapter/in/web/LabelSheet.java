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
     * Horizontal room one label needs: QR, then the slot id beside it. The text is estimated at 0.75em per
     * character rather than measured — Helvetica-Bold caps top out at 0.722em, so this errs wide, and erring
     * wide only ever costs a column, never an overlap.
     */
    private static float contentWidth(Label label) {
        float scale = scaleOf(label);
        float padding = PADDING_1X * scale;
        float text = label.slot().value().length() * 0.75f * fontSize(label);
        return padding + QR_SIZE_1X * scale + padding + text + padding;
    }

    /**
     * The type size, shrunk when the id will not otherwise fit beside its QR.
     *
     * <p>At scale 1.0 a 30mm QR and 40pt type leave room for two characters on a 63.5mm sticker; "E12" wants
     * 67.8mm and "Box1" 78.3mm, and both used to be drawn anyway, running off the edge. The QR is the part
     * that has to work — its module size is already the floor on how small a label can go — so it keeps its
     * size and the type gives way.
     */
    private static float fontSize(Label label) {
        float scale = scaleOf(label);
        float wanted = FONT_SIZE_1X * scale;
        int length = label.slot().value().length();
        if (length == 0) return wanted;

        float padding = PADDING_1X * scale;
        float room = LABEL_W - (padding + QR_SIZE_1X * scale + padding + padding);
        float needed = length * 0.75f * wanted;
        // A hair under, so float arithmetic cannot land a shrunk label a
        // rounding error over the edge it was shrunk to fit inside.
        return needed <= room ? wanted : Math.max(room / (length * 0.75f) * 0.999f, 0f);
    }

    /** One label's spot inside a sticker, as offsets right and down from the sticker's top-left corner. */
    record Placed(Label label, float dx, float dy) {}

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
                            drawLabel(doc, cs, font, placed.label().container(), placed.label().slot(), baseUrl,
                                x + placed.dx(), y + LABEL_H - placed.dy(),
                                QR_SIZE_1X * scale, PADDING_1X * scale, fontSize(placed.label()));
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
