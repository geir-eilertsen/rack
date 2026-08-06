package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void refusesTheNamesTheDataDirectoryHasAlreadyTaken() {
        // A container is a directory under data/, and so are photos/ and
        // projects/. Adding the second took the app down on the next boot: the
        // index walked it, read a project as a drawer, and refused to start
        // because "i-am-restoring-a-quad-606-amplifier" is not a slot id.
        for (String taken : new String[] {"photos", "projects", "labels"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ContainerId(taken));
            assertTrue(e.getMessage().contains("used by the data directory"), e.getMessage());
        }
        // Only those exact names — a rack of photo gear is a fine container.
        assertEquals("photos-box", new ContainerId("photos-box").value());
    }
}
