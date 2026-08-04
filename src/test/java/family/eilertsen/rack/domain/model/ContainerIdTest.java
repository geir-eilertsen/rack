package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerIdTest {

    @Test
    void acceptsLowercaseAndHyphens() {
        new ContainerId("rack");
        new ContainerId("kitchen-bin");
        new ContainerId("a");
        new ContainerId("box42");
    }

    @Test
    void rejectsUppercase() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerId("Rack"));
    }

    @Test
    void rejectsLeadingDigit() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerId("1rack"));
    }

    @Test
    void rejectsUrlUnsafe() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerId("my rack"));
        assertThrows(IllegalArgumentException.class, () -> new ContainerId("my/rack"));
        assertThrows(IllegalArgumentException.class, () -> new ContainerId("my_rack"));
    }

    @Test
    void stringFormShowsTheValue() {
        assertEquals("rack", new ContainerId("rack").toString());
    }
}
