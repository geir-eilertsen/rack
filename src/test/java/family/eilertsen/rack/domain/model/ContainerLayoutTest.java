package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerLayoutTest {

    @Test
    void gridExpands60SlotRack() {
        List<SlotId> slots = ContainerLayout.grid(5, 12);
        assertEquals(60, slots.size());
        assertEquals(new SlotId("A1"), slots.get(0));
        assertEquals(new SlotId("A12"), slots.get(11));
        assertEquals(new SlotId("B1"), slots.get(12));
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
