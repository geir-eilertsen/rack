package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerLayoutTest {

    @Test
    void gridExpands60SlotRackInRowMajorOrder() {
        List<SlotId> slots = ContainerLayout.grid(5, 12);
        assertEquals(60, slots.size());
        // Row 1: A1 B1 C1 D1 E1 (matches a 5-column picker rendering)
        assertEquals(new SlotId("A1"), slots.get(0));
        assertEquals(new SlotId("B1"), slots.get(1));
        assertEquals(new SlotId("E1"), slots.get(4));
        // Row 2 starts at index 5
        assertEquals(new SlotId("A2"), slots.get(5));
        // Row 12 ends at index 59
        assertEquals(new SlotId("E12"), slots.get(59));
    }

    @Test
    void gridRejectsTooManyColumnsForDefaultAlphabet() {
        assertThrows(IllegalArgumentException.class, () -> ContainerLayout.grid(30, 2));
    }

    @Test
    void linearWithoutPrefixNumbersFromOne() {
        List<SlotId> slots = ContainerLayout.linear(4, "");
        assertEquals(List.of(new SlotId("1"), new SlotId("2"), new SlotId("3"), new SlotId("4")), slots);
    }

    @Test
    void linearWithPrefixPrepends() {
        List<SlotId> slots = ContainerLayout.linear(3, "b");
        assertEquals(List.of(new SlotId("b1"), new SlotId("b2"), new SlotId("b3")), slots);
    }

    @Test
    void linearWithNullPrefixIsSameAsEmpty() {
        assertEquals(ContainerLayout.linear(2, ""), ContainerLayout.linear(2, null));
    }
}
