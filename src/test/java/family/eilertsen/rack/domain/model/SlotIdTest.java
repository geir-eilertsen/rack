package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlotIdTest {

    @Test
    void acceptsCommonForms() {
        new SlotId("A1");
        new SlotId("E12");
        new SlotId("1");
        new SlotId("12");
        new SlotId("bin-3");
        new SlotId("topLeft");
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new SlotId(""));
    }

    @Test
    void rejectsTooLong() {
        assertThrows(IllegalArgumentException.class, () -> new SlotId("a-far-too-long-name"));
    }

    @Test
    void rejectsUrlUnsafe() {
        assertThrows(IllegalArgumentException.class, () -> new SlotId("A 1"));
        assertThrows(IllegalArgumentException.class, () -> new SlotId("A/1"));
        assertThrows(IllegalArgumentException.class, () -> new SlotId("A_1"));
    }

    @Test
    void rejectsLeadingHyphen() {
        assertThrows(IllegalArgumentException.class, () -> new SlotId("-x"));
    }

    @Test
    void stringFormShowsTheValue() {
        assertEquals("B4", new SlotId("B4").toString());
    }
}
