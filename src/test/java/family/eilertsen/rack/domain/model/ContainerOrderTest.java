package family.eilertsen.rack.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerOrderTest {

    private static Container named(String id, String name) {
        return new Container(new ContainerId(id), name, ContainerLayout.linear(1, ""), 1.0f, "slot", null, null);
    }

    private static List<String> sorted(Container... containers) {
        List<Container> list = new ArrayList<>(List.of(containers));
        list.sort(Container.BY_NAME);
        return list.stream().map(c -> c.id().value()).toList();
    }

    @Test
    void ordersByTheNameOnTheFrontRatherThanRegistrationOrder() {
        assertEquals(
            List.of("lab", "box01", "rack", "vaskerom"),
            sorted(named("rack", "Skuffereol"),
                   named("lab", "Elektronikklab"),
                   named("box01", "Plastboks 1"),
                   named("vaskerom", "Vaskerom")));
    }

    @Test
    void readsARunOfDigitsAsANumber() {
        assertEquals(
            List.of("b2", "b10"),
            sorted(named("b10", "Plastboks 10"), named("b2", "Plastboks 2")));
    }

    @Test
    void ignoresCase() {
        assertEquals(
            List.of("a", "b"), sorted(named("b", "beta"), named("a", "Alpha")));
    }

    @Test
    void fallsBackToTheIdWhenThereIsNoName() {
        assertEquals(
            List.of("aa", "zz"), sorted(named("zz", null), named("aa", "")));
    }

    /** Same label, so the order has to come from somewhere that cannot tie. */
    @Test
    void breaksTiesOnTheIdSoTheOrderIsStable() {
        assertEquals(
            List.of("first", "second"),
            sorted(named("second", "Spare box"), named("first", "Spare box")));
    }

    @Test
    void leadingZeroesDoNotMakeANumberBigger() {
        assertEquals(
            List.of("b7", "b12"), sorted(named("b12", "Box 12"), named("b7", "Box 007")));
    }
}
