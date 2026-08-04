package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.SlotId;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LabelSheetTest {

    private static final String BASE = "https://rack.example";

    private static final Container LAB =
        new Container(new ContainerId("lab"), "Lab", ContainerLayout.linear(11, ""), 1.0f);
    private static final Container BIN =
        new Container(new ContainerId("bin"), "Bin", ContainerLayout.linear(6, "b"), 0.4f);

    @Test
    void fullScaleLabelsGetOnePhysicalLabelEach() {
        assertThat(LabelSheet.positionCount(labelsFor(LAB, 11))).isEqualTo(11);
    }

    @Test
    void fourSmallLabelsShareOneStickerAsATwoByTwoGrid() {
        // 0.4 scale: content is 13.6mm tall and ~22.9mm wide, so a 63.5 x 38.1mm sticker takes 2 columns x 2 rows.
        assertThat(LabelSheet.pack(labelsFor(BIN, 4))).hasSize(1);
        assertThat(LabelSheet.pack(labelsFor(BIN, 5))).hasSize(2);
        assertThat(LabelSheet.positionCount(labelsFor(BIN, 6))).isEqualTo(2);
    }

    @Test
    void theGridFillsAcrossBeforeDroppingToTheNextRow() {
        List<LabelSheet.Placed> sticker = LabelSheet.pack(labelsFor(BIN, 4)).get(0);

        assertThat(sticker).hasSize(4);
        assertThat(sticker.get(0).dy()).isEqualTo(sticker.get(1).dy());   // first row
        assertThat(sticker.get(1).dx()).isGreaterThan(sticker.get(0).dx());
        assertThat(sticker.get(2).dy()).isGreaterThan(sticker.get(0).dy()); // wrapped to row two
        assertThat(sticker.get(2).dx()).isEqualTo(sticker.get(0).dx());
    }

    @Test
    void packedLabelsNeverOverlapOrLeaveTheSticker() {
        List<LabelSheet.Label> mixed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mixed.addAll(labelsFor(BIN, 6));
            mixed.addAll(labelsFor(LAB, 2));
        }

        for (List<LabelSheet.Placed> sticker : LabelSheet.pack(mixed)) {
            for (LabelSheet.Placed p : sticker) {
                assertThat(p.dx()).isGreaterThanOrEqualTo(0);
                assertThat(p.dy()).isGreaterThanOrEqualTo(0);
                assertThat(p.dx() + LabelSheet.widthOf(p.label())).isLessThanOrEqualTo(LabelSheet.stickerWidth());
                assertThat(p.dy() + LabelSheet.heightOf(p.label())).isLessThanOrEqualTo(LabelSheet.stickerHeight());
            }
            for (int i = 0; i < sticker.size(); i++) {
                for (int j = i + 1; j < sticker.size(); j++) {
                    assertThat(overlaps(sticker.get(i), sticker.get(j)))
                        .as("%s overlaps %s", sticker.get(i).label().slot(), sticker.get(j).label().slot())
                        .isFalse();
                }
            }
        }
    }

    private static boolean overlaps(LabelSheet.Placed a, LabelSheet.Placed b) {
        boolean apart = a.dx() + LabelSheet.widthOf(a.label()) <= b.dx()
            || b.dx() + LabelSheet.widthOf(b.label()) <= a.dx()
            || a.dy() + LabelSheet.heightOf(a.label()) <= b.dy()
            || b.dy() + LabelSheet.heightOf(b.label()) <= a.dy();
        return !apart;
    }

    @Test
    void aPartlyFilledStickerStillCountsAsOne() {
        assertThat(LabelSheet.positionCount(labelsFor(BIN, 3))).isEqualTo(1);
        assertThat(LabelSheet.positionCount(labelsFor(BIN, 1))).isEqualTo(1);
    }

    @Test
    void aFullScaleLabelIsNotPackedBehindASmallOne() {
        List<LabelSheet.Label> mixed = new ArrayList<>();
        mixed.addAll(labelsFor(BIN, 1));
        mixed.addAll(labelsFor(LAB, 1));
        mixed.addAll(labelsFor(BIN, 1));

        // 13.6mm then 34mm overflows the slot, so the full-scale label starts a fresh position.
        assertThat(LabelSheet.pack(mixed)).hasSize(3);
    }

    @Test
    void packingShrinksTheSheetCount() throws IOException {
        List<LabelSheet.Label> forty = labelsFor(BIN, 6);
        for (int i = 0; i < 6; i++) forty = concat(forty, labelsFor(BIN, 6));

        assertThat(LabelSheet.positionCount(forty)).isLessThan(forty.size());
        assertThat(pageCount(LabelSheet.build(BASE, forty, 0))).isEqualTo(1);
    }

    @Test
    void startsAtTheGivenSheetOffset() throws IOException {
        // 11 stickers already used, so the run starts at position 12 and the sheet takes 10 more before spilling.
        List<LabelSheet.Label> labels = new ArrayList<>();
        labels.addAll(labelsFor(BIN, 6));
        labels.addAll(labelsFor(LAB, 4));

        byte[] pdf = LabelSheet.build(BASE, labels, 11);

        assertThat(pageCount(pdf)).isEqualTo(1);
    }

    @Test
    void spillsOntoASecondSheetWhenTheOffsetLeavesTooLittleRoom() throws IOException {
        List<LabelSheet.Label> labels = new ArrayList<>(labelsFor(LAB, 11));

        assertThat(pageCount(LabelSheet.build(BASE, labels, 11))).isEqualTo(2);
        assertThat(pageCount(LabelSheet.build(BASE, labels, 0))).isEqualTo(1);
    }

    @Test
    void packsMixedScalesIndependently() throws IOException {
        List<LabelSheet.Label> labels = new ArrayList<>();
        labels.addAll(labelsFor(LAB, 3));
        labels.addAll(labelsFor(BIN, 3));

        byte[] pdf = LabelSheet.build(BASE, labels, 0);

        assertThat(pdf).isNotEmpty();
        assertThat(pageCount(pdf)).isEqualTo(1);
    }

    @Test
    void perContainerBuildStillWorks() throws IOException {
        byte[] pdf = LabelSheet.build(BASE, LAB, LAB.slots(), 0);

        assertThat(pageCount(pdf)).isEqualTo(1);
    }

    @Test
    void anEmptyRunStillProducesOneBlankSheet() throws IOException {
        assertThat(pageCount(LabelSheet.build(BASE, List.of(), 0))).isEqualTo(1);
    }

    private static List<LabelSheet.Label> concat(List<LabelSheet.Label> a, List<LabelSheet.Label> b) {
        List<LabelSheet.Label> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<LabelSheet.Label> labelsFor(Container c, int count) {
        List<LabelSheet.Label> labels = new ArrayList<>();
        for (SlotId sid : c.slots().subList(0, count)) labels.add(new LabelSheet.Label(c, sid));
        return labels;
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }
}
