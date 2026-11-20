package family.eilertsen.rack.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import family.eilertsen.rack.adapter.out.filesystem.FilesystemShoppingList;
import family.eilertsen.rack.domain.model.ShopPlace;
import family.eilertsen.rack.domain.model.ShoppingEntry;
import family.eilertsen.rack.domain.port.HouseInbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShopArrivalsTest {

    // Two Leroy branches far apart, and a Jula.
    static final double LEROY_A_LAT = 59.9000, LEROY_A_LON = 10.8000;
    static final double LEROY_B_LAT = 60.3900, LEROY_B_LON = 5.3300;
    static final double JULA_LAT = 59.9500, JULA_LON = 10.9000;
    static final double HOME_LAT = 59.7000, HOME_LON = 10.5000;

    @TempDir Path dataDir;
    FilesystemShoppingList list;
    final List<String> told = new ArrayList<>();
    boolean brokerTakes = true;
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    ShopArrivals arrivals;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
        list = new FilesystemShoppingList(dataDir.toString(), mapper);
        list.savePlaces(List.of(
            new ShopPlace("a", "Leroy", LEROY_A_LAT, LEROY_A_LON, 10.0, Instant.EPOCH),
            new ShopPlace("b", "Leroy", LEROY_B_LAT, LEROY_B_LON, 10.0, Instant.EPOCH),
            new ShopPlace("c", "Jula", JULA_LAT, JULA_LON, 10.0, Instant.EPOCH)));
        arrivals = new ShopArrivals(list, new HouseInbox() {
            public boolean available() { return true; }
            public boolean tell(String owner, String text) {
                if (!brokerTakes) return false;
                told.add(owner + ": " + text);
                return true;
            }
        }, clock);
    }

    private void need(String what, String store, String qty) {
        list.save(new ShoppingEntry(java.util.UUID.randomUUID().toString().substring(0, 8), what, store,
            null, null, qty, null, null, null, Instant.EPOCH, null));
    }

    @Test
    void arrivingAtAShopWithThingsOnTheListSaysWhat() {
        need("GU10 bulb", "leroy ", "4");
        need("Wall plugs", "Leroy", null);
        need("Batteries", null, null);
        need("Cable ties", "Jula", null);

        arrivals.position("pixel", "geir", HOME_LAT, HOME_LON, 10.0);
        assertThat(told).isEmpty();

        assertThat(arrivals.position("pixel", "geir", LEROY_A_LAT + 0.0005, LEROY_A_LON, 20.0))
            .contains("At Leroy: 2 things on the list — GU10 bulb (4), Wall plugs. Plus 1 for any shop.");
        assertThat(told).containsExactly("geir: At Leroy: 2 things on the list — GU10 bulb (4), Wall plugs. Plus 1 for any shop.");
    }

    @Test
    void aLineAlreadyOrderedIsNotOneToPickUp() {
        need("GU10 bulb", "Leroy", null);
        list.save(new ShoppingEntry("ordered1", "Wall plugs", "Leroy", null, null, null, null, null, null,
            Instant.EPOCH, null, Instant.EPOCH, "Order 4411"));

        assertThat(arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0))
            .contains("At Leroy: 1 thing on the list — GU10 bulb.");
    }

    @Test
    void aLineForSeveralShopsIsOneToPickUpAtEach() {
        list.save(new ShoppingEntry("multi1", "Wall plugs", "Jula", null, null, null, null, null, null,
            Instant.EPOCH, null, null, null, List.of("Leroy")));

        assertThat(arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0))
            .contains("At Leroy: 1 thing on the list — Wall plugs.");
    }

    @Test
    void anyBranchSavedUnderTheNameCounts() {
        need("GU10 bulb", "Leroy", null);
        arrivals.position("pixel", "geir", LEROY_B_LAT, LEROY_B_LON, 15.0);
        assertThat(told).hasSize(1);
    }

    @Test
    void walkingAroundInsideSaysItOnce() {
        need("GU10 bulb", "Leroy", null);
        arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0);
        arrivals.position("pixel", "geir", LEROY_A_LAT + 0.001, LEROY_A_LON, 10.0);
        assertThat(told).hasSize(1);
    }

    @Test
    void comingBackForTheForgottenThingIsTheSameTrip() {
        need("GU10 bulb", "Leroy", null);
        arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0);
        arrivals.position("pixel", "geir", HOME_LAT, HOME_LON, 10.0);
        clock.advance(Duration.ofMinutes(30));
        arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0);
        assertThat(told).hasSize(1);

        arrivals.position("pixel", "geir", HOME_LAT, HOME_LON, 10.0);
        clock.advance(ShopArrivals.QUIET);
        arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0);
        assertThat(told).hasSize(2);
    }

    @Test
    void aShopWithNothingForItIsNotNews() {
        need("Batteries", null, null);
        need("Already got", "Jula", null);
        list.save(list.all().get(1).withBought(Instant.EPOCH));
        arrivals.position("pixel", "geir", JULA_LAT, JULA_LON, 10.0);
        assertThat(told).isEmpty();
    }

    @Test
    void farFromEverySpotIsNowhere() {
        need("GU10 bulb", "Leroy", null);
        // ~400 m off: past 150 m plus the 100 m most a poor fix can add.
        arrivals.position("pixel", "geir", LEROY_A_LAT + 0.0036, LEROY_A_LON, 900.0);
        assertThat(told).isEmpty();
    }

    @Test
    void aPhoneOfNobodyIsNobodyToTell() {
        need("GU10 bulb", "Leroy", null);
        arrivals.position("pixel", null, LEROY_A_LAT, LEROY_A_LON, 10.0);
        arrivals.position("pixel", "", LEROY_A_LAT, LEROY_A_LON, 10.0);
        assertThat(told).isEmpty();
    }

    @Test
    void aMessageTheBrokerRefusedIsTriedAtTheNextArrival() {
        need("GU10 bulb", "Leroy", null);
        brokerTakes = false;
        arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0);
        brokerTakes = true;
        arrivals.position("pixel", "geir", HOME_LAT, HOME_LON, 10.0);
        arrivals.position("pixel", "geir", LEROY_A_LAT, LEROY_A_LON, 10.0);
        assertThat(told).hasSize(1);
    }

    @Test
    void aLongListIsCut() {
        for (int i = 1; i <= 6; i++) need("Thing " + i, "Jula", null);
        arrivals.position("pixel", "geir", JULA_LAT, JULA_LON, 10.0);
        assertThat(told).containsExactly("geir: At Jula: 6 things on the list — Thing 1, Thing 2, Thing 3, Thing 4 and 2 more.");
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
