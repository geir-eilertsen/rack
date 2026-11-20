package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.ShopPlace;
import family.eilertsen.rack.domain.model.ShoppingEntry;
import family.eilertsen.rack.domain.port.HouseInbox;
import family.eilertsen.rack.domain.port.ShoppingList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "You're at Leroy, and the list has three things for Leroy": said once, on
 * the phone, when a phone that reports its position arrives at a saved shop.
 *
 * <p>Positions arrive from Vaier's broker and are not kept — only which shop
 * each phone was last at, in memory, so that standing in the aisle while the
 * phone reports again does not say it twice. Nothing about where anybody was
 * is written to disk or to the log.
 */
@Service
public class ShopArrivals {

    private static final Logger log = LoggerFactory.getLogger(ShopArrivals.class);

    /** The same reach as the page: a big store is a hundred metres across. */
    static final double NEAR = 150;
    static final double MAX_SLACK = 100;
    /** Leaving for the car and coming back for the forgotten thing is one trip. */
    static final Duration QUIET = Duration.ofHours(3);
    static final int NAMED = 4;

    private final ShoppingList list;
    private final HouseInbox inbox;
    private final Clock clock;

    private final Map<String, String> atShop = new HashMap<>();       // phone -> store key
    private final Map<String, Instant> lastTold = new HashMap<>();    // owner|store key -> when

    @Autowired
    public ShopArrivals(ShoppingList list, HouseInbox inbox) {
        this(list, inbox, Clock.systemUTC());
    }

    ShopArrivals(ShoppingList list, HouseInbox inbox, Clock clock) {
        this.list = list;
        this.inbox = inbox;
        this.clock = clock;
    }

    public boolean on() {
        return inbox.available();
    }

    /**
     * A phone said where it is. {@code owner} is whose inbox it reads; a phone
     * with no owner is somebody's position and nobody to tell, so it is ignored.
     *
     * @return the message sent, if one was
     */
    public synchronized Optional<String> position(String phone, String owner, double lat, double lon, Double accuracy) {
        if (phone == null || owner == null || owner.isBlank()) return Optional.empty();
        Optional<ShopPlace> shop = shopAt(lat, lon, accuracy);
        String key = shop.map(p -> key(p.store())).orElse(null);
        String was = key == null ? atShop.remove(phone) : atShop.put(phone, key);
        if (key == null || key.equals(was)) return Optional.empty();

        String store = shop.get().store();
        Optional<String> text = message(store);
        if (text.isEmpty()) return Optional.empty();
        String told = owner + "|" + key;
        Instant now = Instant.now(clock);
        Instant last = lastTold.get(told);
        if (last != null && last.plus(QUIET).isAfter(now)) return Optional.empty();
        if (!inbox.tell(owner, text.get())) return Optional.empty();
        lastTold.put(told, now);
        log.info("Told {} what the list has for {}", owner, store);
        return text;
    }

    /** The nearest saved spot within reach, if any. */
    Optional<ShopPlace> shopAt(double lat, double lon, Double accuracy) {
        double reach = NEAR + Math.min(accuracy == null || !(accuracy >= 0) ? 0 : accuracy, MAX_SLACK);
        ShopPlace best = null;
        double bestMetres = Double.MAX_VALUE;
        for (ShopPlace p : list.places()) {
            double m = p.metresTo(lat, lon);
            if (m <= reach && m < bestMetres) {
                best = p;
                bestMetres = m;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * What to say at {@code store}: its own lines, named, and how many could be
     * bought anywhere. Nothing when nothing is on the list for this store —
     * walking into Jula is not news.
     */
    Optional<String> message(String store) {
        // An ordered line is on its way, not something to pick up here.
        List<ShoppingEntry> open = list.all().stream().filter(ShoppingEntry::open).toList();
        // A line that may come from several shops is one to pick up at each of them.
        List<ShoppingEntry> here = open.stream().filter(e -> e.soldAt(store)).toList();
        if (here.isEmpty()) return Optional.empty();
        long anywhere = open.stream().filter(e -> e.store() == null).count();
        StringBuilder text = new StringBuilder("At ").append(store).append(": ")
            .append(here.size()).append(here.size() == 1 ? " thing" : " things").append(" on the list — ");
        for (int i = 0; i < Math.min(here.size(), NAMED); i++) {
            if (i > 0) text.append(", ");
            ShoppingEntry e = here.get(i);
            text.append(e.what());
            if (e.qty() != null) text.append(" (").append(e.qty()).append(')');
        }
        if (here.size() > NAMED) text.append(" and ").append(here.size() - NAMED).append(" more");
        if (anywhere > 0) text.append(". Plus ").append(anywhere).append(" for any shop");
        return Optional.of(text.append('.').toString());
    }

    private static String key(String store) {
        return store.trim().toLowerCase(Locale.ROOT);
    }
}
