package family.eilertsen.rack.domain.model;

import java.util.ArrayList;
import java.util.List;

public final class ContainerLayout {

    private ContainerLayout() {}

    public static List<SlotId> grid(int cols, int rows) {
        return grid(cols, rows, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    }

    public static List<SlotId> grid(int cols, int rows, String colLabels) {
        if (cols > colLabels.length()) {
            throw new IllegalArgumentException(
                "Not enough column labels (" + colLabels.length() + ") for " + cols + " columns");
        }
        List<SlotId> slots = new ArrayList<>(cols * rows);
        for (int c = 0; c < cols; c++) {
            char letter = colLabels.charAt(c);
            for (int r = 1; r <= rows; r++) {
                slots.add(new SlotId("" + letter + r));
            }
        }
        return List.copyOf(slots);
    }

    public static List<SlotId> linear(int count, String prefix) {
        String p = prefix == null ? "" : prefix;
        List<SlotId> slots = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            slots.add(new SlotId(p + i));
        }
        return List.copyOf(slots);
    }
}
