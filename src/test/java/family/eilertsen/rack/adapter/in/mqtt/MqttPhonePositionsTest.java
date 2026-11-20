package family.eilertsen.rack.adapter.in.mqtt;

import family.eilertsen.rack.application.ShopArrivals;
import family.eilertsen.rack.domain.port.HouseInbox;
import family.eilertsen.rack.domain.port.ShoppingList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MqttPhonePositionsTest {

    final List<String> heard = new ArrayList<>();
    final ShopArrivals arrivals = new ShopArrivals((ShoppingList) null, (HouseInbox) null) {
        @Override
        public synchronized Optional<String> position(String phone, String owner, double lat, double lon, Double accuracy) {
            heard.add(phone + " " + owner + " " + lat + "," + lon + " ±" + accuracy);
            return Optional.empty();
        }
    };
    final MqttPhonePositions positions = new MqttPhonePositions(arrivals, "", "", "");

    @Test
    void aFreshReportCarriesTheOwnerAndAccuracyThatCameBeforeIt() {
        positions.heard("roaming/phone/pixel-8/owner", "geir", true);
        positions.heard("roaming/phone/pixel-8/accuracy", "12", false);
        positions.heard("roaming/phone/pixel-8/location", "59.912345,10.754321", false);
        assertThat(heard).containsExactly("pixel-8 geir 59.912345,10.754321 ±12.0");
    }

    @Test
    void whereAPhoneWasWhenRackSubscribedIsNotAnArrival() {
        positions.heard("roaming/phone/pixel-8/owner", "geir", true);
        positions.heard("roaming/phone/pixel-8/location", "59.9,10.7", true);
        assertThat(heard).isEmpty();
    }

    @Test
    void anythingElseIsIgnored() {
        positions.heard("roaming/phone/pixel-8/location", "not a place", false);
        positions.heard("roaming/phone/pixel-8/wifi", "Apalveien", false);
        positions.heard("roaming/car/x/location", "59.9,10.7", false);
        positions.heard("roaming/phone/pixel-8/extra/location", "59.9,10.7", false);
        assertThat(heard).isEmpty();
    }
}
