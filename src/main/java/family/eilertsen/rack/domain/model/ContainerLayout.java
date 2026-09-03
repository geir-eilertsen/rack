package family.eilertsen.rack.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ContainerLayout {

    private ContainerLayout() {}

    /** The letters a grid's columns are named from, in the order they are spent. */
    public static final String COLUMN_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static List<SlotId> grid(int cols, int rows) {
        return grid(cols, rows, COLUMN_LETTERS);
    }

    public static List<SlotId> grid(int cols, int rows, String colLabels) {
        return grid(cols, rows, colLabels, 1);
    }

    /**
     * A grid whose rows are numbered from {@code firstRow} — how a band of a
     * sectioned container is laid out. Bands letter their columns from A alike and
     * carry the numbering on, so a row of two large drawers under four rows of six
     * small ones is A5 B5: unique without anybody choosing letters, and legible as
     * its own row because no other row shares its number. Restarting the numbering
     * instead would make a band of one row indistinguishable from a continuation of
     * the row above it, which is the one thing the ids have to be able to say.
     */
    public static List<SlotId> grid(int cols, int rows, String colLabels, int firstRow) {
        if (cols > colLabels.length()) {
            throw new IllegalArgumentException(
                "Not enough column labels (" + colLabels.length() + ") for " + cols + " columns");
        }
        List<SlotId> slots = new ArrayList<>(cols * rows);
        for (int r = firstRow; r < firstRow + rows; r++) {
            for (int c = 0; c < cols; c++) {
                char letter = colLabels.charAt(c);
                slots.add(new SlotId("" + letter + r));
            }
        }
        return List.copyOf(slots);
    }

    /**
     * Concatenates blocks of slots into the one flat list a container holds — a
     * cabinet with a band of small drawers over a couple of large ones, which is
     * neither a grid nor a numbered run but is both, one after the other.
     *
     * <p>Nothing about the sectioning is stored, because nothing needs to be. Each
     * block is emitted row-major, so a row is a run of consecutive ids sharing a
     * number, and the shape can be read back off the ids the same way a regular
     * grid's width already is.
     *
     * <p>Blocks are only unique between themselves if they are lettered or prefixed
     * apart, and two slots with one id is a drawer that cannot be addressed — so
     * that is a refusal rather than something to notice later.
     */
    public static List<SlotId> sections(List<List<SlotId>> blocks) {
        List<SlotId> all = new ArrayList<>();
        Set<SlotId> seen = new LinkedHashSet<>();
        for (List<SlotId> block : blocks) {
            for (SlotId slot : block) {
                if (!seen.add(slot)) {
                    throw new IllegalArgumentException(
                        "Two slots would be called " + slot.value() + " — give the sections different column letters or prefixes");
                }
                all.add(slot);
            }
        }
        if (all.isEmpty()) throw new IllegalArgumentException("A container needs at least one slot");
        return List.copyOf(all);
    }

    public static List<SlotId> linear(int count, String prefix) {
        String p = prefix == null ? "" : prefix;
        // Numbering one thing is meaningless: a prefix of "Box2" and a count of
        // one meant the place is called Box2, and produced "Box21".
        if (count == 1 && !p.isBlank()) return List.of(new SlotId(p));
        List<SlotId> slots = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            slots.add(new SlotId(p + i));
        }
        return List.copyOf(slots);
    }
}
