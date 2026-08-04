package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawerIdTest {

    @Test
    void acceptsCornersOfTheGrid() {
        new DrawerId("A1");
        new DrawerId("E12");
        new DrawerId("C7");
    }

    @Test
    void rejectsOutOfRangeRow() {
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("A0"));
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("A13"));
    }

    @Test
    void rejectsOutOfRangeColumn() {
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("F1"));
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("a1"));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> new DrawerId(""));
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("A"));
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("1A"));
        assertThrows(IllegalArgumentException.class, () -> new DrawerId("A 1"));
    }

    @Test
    void stringFormShowsTheValue() {
        assertEquals("B4", new DrawerId("B4").toString());
    }

    @Test
    void allEnumeratesAllSixty() {
        var ids = DrawerId.all();
        assertEquals(60, ids.size());
        assertEquals(new DrawerId("A1"), ids.get(0));
        assertEquals(new DrawerId("E12"), ids.get(59));
    }
}
