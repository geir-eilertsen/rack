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
    @Test
    void oneThingWithANameIsNotNumbered() {
        // A prefix of "Box2" and a count of one meant the place is called Box2.
        // It produced "Box21", which is what got printed on the label.
        assertEquals(List.of(new SlotId("Box2")), ContainerLayout.linear(1, "Box2"));
    }

    @Test
    void oneThingWithoutANameStillNeedsOne() {
        assertEquals(List.of(new SlotId("1")), ContainerLayout.linear(1, ""));
        assertEquals(List.of(new SlotId("1")), ContainerLayout.linear(1, null));
    }

    @Test
    void moreThanOneIsStillNumbered() {
        assertEquals(List.of(new SlotId("b1"), new SlotId("b2"), new SlotId("b3")),
            ContainerLayout.linear(3, "b"));
    }


    @Test
    void gridCanStartItsNumberingPartWayDown() {
        assertEquals(List.of(new SlotId("A5"), new SlotId("B5")),
            ContainerLayout.grid(2, 1, ContainerLayout.COLUMN_LETTERS, 5));
    }

    @Test
    void sectionsConcatenateIntoOneFlatList() {
        List<SlotId> slots = ContainerLayout.sections(List.of(
            ContainerLayout.grid(6, 4),
            ContainerLayout.grid(2, 1, ContainerLayout.COLUMN_LETTERS, 5)));

        assertEquals(26, slots.size());
        assertEquals(new SlotId("F1"), slots.get(5));
        assertEquals(new SlotId("A2"), slots.get(6));
        assertEquals(new SlotId("A5"), slots.get(24));
        assertEquals(new SlotId("B5"), slots.get(25));
    }

    @Test
    void twoSlotsWithOneIdIsRefused() {
        // Not a shape to notice later: a second A1 is a drawer that cannot be
        // addressed, and one of the two would be unreachable.
        assertThrows(IllegalArgumentException.class, () -> ContainerLayout.sections(List.of(
            ContainerLayout.grid(2, 1),
            ContainerLayout.grid(2, 1))));
    }

    @Test
    void sectionsOfNothingIsNotAContainer() {
        assertThrows(IllegalArgumentException.class, () -> ContainerLayout.sections(List.of()));
    }
}
