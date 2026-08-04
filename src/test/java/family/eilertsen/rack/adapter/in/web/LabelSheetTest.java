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
    void twoSmallLabelsShareOnePhysicalLabel() {
        // 0.4 scale: 12mm QR + 1.6mm padding = 13.6mm, so two fit inside the 38.1mm L7160 slot.
        assertThat(LabelSheet.pack(labelsFor(BIN, 2))).hasSize(1);
        assertThat(LabelSheet.pack(labelsFor(BIN, 6))).hasSize(3);
        assertThat(LabelSheet.positionCount(labelsFor(BIN, 6))).isEqualTo(3);
    }

    @Test
    void anOddSmallLabelStillClaimsItsOwnPhysicalLabel() {
        assertThat(LabelSheet.positionCount(labelsFor(BIN, 3))).isEqualTo(2);
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
    void continuesOneSheetAcrossContainers() throws IOException {
        // 11 labels already peeled, so the run starts at position 12 and the sheet takes 10 more before spilling.
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
    void mixesContainerScalesOnOneSheet() throws IOException {
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
