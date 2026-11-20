package family.eilertsen.rack.adapter.in.mqtt;

import family.eilertsen.rack.application.ShopArrivals;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Where the household's phones are, as the Vaier app reports it: retained
 * values under {@code roaming/phone/<phone id>/} on Vaier's broker, sent after
 * a move of 100 m or a change of Wi-Fi. {@code owner} says whose inbox the
 * phone reads, {@code accuracy} how sure the fix is, {@code location} is
 * {@code "lat,lon"} and comes last.
 *
 * <p>A retained location is where a phone was when it last reported, possibly
 * hours ago, and arrives on every subscribe — so it is never treated as an
 * arrival; only a fresh report is. Positions pass straight through to
 * {@link ShopArrivals} and are not kept here beyond the owner and accuracy
 * that go with the next one.
 */
@Component
public class MqttPhonePositions {

    private static final Logger log = LoggerFactory.getLogger(MqttPhonePositions.class);
    private static final String TOPICS = "roaming/phone/+/+";
    private static final int TIMEOUT_SECONDS = 10;
    private static final long FIRST_WAIT = 30, LONGEST_WAIT = 600;

    private final ShopArrivals arrivals;
    private final String url;
    private final String username;
    private final String password;

    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private final Map<String, Double> accuracies = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-positions");
        t.setDaemon(true);
        return t;
    });
    private volatile MqttClient client;
    private long wait = FIRST_WAIT;

    public MqttPhonePositions(ShopArrivals arrivals,
                              @Value("${rack.notify.mqtt-url:}") String url,
                              @Value("${rack.notify.mqtt-username:}") String username,
                              @Value("${rack.notify.mqtt-password:}") String password) {
        this.arrivals = arrivals;
        this.url = url == null ? "" : url.trim();
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (url.isEmpty()) {
            log.info("No rack.notify.mqtt-url: not listening for phone positions");
            return;
        }
        worker.execute(this::connect);
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
        MqttClient c = client;
        if (c == null) return;
        try { if (c.isConnected()) c.disconnect(2_000); } catch (MqttException ignored) { }
        try { c.close(); } catch (MqttException ignored) { }
    }

    /** The first connect is retried here; after one success Paho reconnects on its own. */
    private void connect() {
        try {
            MqttClient next = new MqttClient(url, "rack-positions", new MemoryPersistence());
            next.setTimeToWait(TIMEOUT_SECONDS * 1000L);
            next.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    try {
                        next.subscribe(TOPICS, 1);
                        log.info("Listening for phone positions on {}", serverURI);
                    } catch (MqttException e) {
                        log.warn("Could not subscribe to phone positions: {}", e.toString());
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.info("Lost the broker ({}); reconnecting", cause == null ? "?" : cause.toString());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        heard(topic, new String(message.getPayload(), StandardCharsets.UTF_8), message.isRetained());
                    } catch (RuntimeException e) {
                        // Never let one odd message take the subscription down.
                        log.warn("Ignoring a phone message: {}", e.toString());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) { }
            });
            MqttConnectOptions options = options();
            options.setAutomaticReconnect(true);
            options.setKeepAliveInterval(60);
            next.connect(options);
            client = next;
            wait = FIRST_WAIT;
        } catch (MqttException | RuntimeException e) {
            log.warn("Could not reach the broker for phone positions ({}); trying again in {}s", e.toString(), wait);
            if (!worker.isShutdown()) worker.schedule(this::connect, wait, TimeUnit.SECONDS);
            wait = Math.min(wait * 2, LONGEST_WAIT);
        }
    }

    /** One value under {@code roaming/phone/<id>/<what>}. */
    void heard(String topic, String value, boolean retained) {
        String[] parts = topic.split("/");
        if (parts.length != 4 || !parts[0].equals("roaming") || !parts[1].equals("phone")) return;
        String phone = parts[2];
        switch (parts[3]) {
            case "owner" -> {
                if (value.isBlank()) owners.remove(phone); else owners.put(phone, value.trim());
            }
            case "accuracy" -> {
                try {
                    accuracies.put(phone, Double.parseDouble(value.trim()));
                } catch (NumberFormatException e) {
                    accuracies.remove(phone);
                }
            }
            case "location" -> {
                if (retained) return;
                String[] latLon = value.split(",");
                if (latLon.length != 2) return;
                double lat, lon;
                try {
                    lat = Double.parseDouble(latLon[0].trim());
                    lon = Double.parseDouble(latLon[1].trim());
                } catch (NumberFormatException e) {
                    return;
                }
                arrivals.position(phone, owners.get(phone), lat, lon, accuracies.get(phone));
            }
            default -> { }
        }
    }

    /** As {@code MqttHouseInbox} logs in — see there for why Paho's own hostname check is kept. */
    private MqttConnectOptions options() {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setCleanSession(true);
        o.setConnectionTimeout(TIMEOUT_SECONDS);
        if (username != null && !username.isBlank()) o.setUserName(username);
        if (password != null && !password.isEmpty()) o.setPassword(password.toCharArray());
        if (url.startsWith("ssl://")) {
            o.setSocketFactory(SSLSocketFactory.getDefault());
            o.setHttpsHostnameVerificationEnabled(true);
        }
        return o;
    }
}
