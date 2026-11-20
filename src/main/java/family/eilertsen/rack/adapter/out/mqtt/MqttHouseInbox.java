package family.eilertsen.rack.adapter.out.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.eilertsen.rack.domain.port.HouseInbox;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A line in the Vaier app: published on {@code inbox/<owner>} of Vaier's
 * broker, at QoS 1 and not retained, so the phone's session holds it while
 * the phone is out of reach. The shape is the one the app reads —
 * {@code {id, house, kind, text}} — with rack as the house it came from.
 *
 * <p>Connects for each message rather than holding a connection: arriving at
 * a shop is a few times a week.
 */
@Component
public class MqttHouseInbox implements HouseInbox {

    private static final Logger log = LoggerFactory.getLogger(MqttHouseInbox.class);
    private static final int TIMEOUT_SECONDS = 10;

    private final String url;
    private final String username;
    private final String password;
    private final ObjectMapper json = new ObjectMapper();

    public MqttHouseInbox(@Value("${rack.notify.mqtt-url:}") String url,
                          @Value("${rack.notify.mqtt-username:}") String username,
                          @Value("${rack.notify.mqtt-password:}") String password) {
        this.url = url == null ? "" : url.trim();
        this.username = username;
        this.password = password;
    }

    @Override
    public boolean available() {
        return !url.isEmpty();
    }

    @Override
    public boolean tell(String owner, String text) {
        if (!available()) return false;
        // One topic level: the owner's short id is lowercase letters and digits.
        if (owner == null || !owner.matches("[a-z0-9-]{1,64}")) return false;
        MqttClient client = null;
        try {
            client = new MqttClient(url, "rack-inbox-" + UUID.randomUUID().toString().substring(0, 8), new MemoryPersistence());
            client.setTimeToWait(TIMEOUT_SECONDS * 1000L);
            client.connect(options(url, username, password, true));
            MqttMessage message = new MqttMessage(payload(text).getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(false);
            client.publish("inbox/" + owner, message);
            client.disconnect(TIMEOUT_SECONDS * 1000L);
            return true;
        } catch (MqttException | RuntimeException e) {
            log.warn("The broker did not take a message for {}: {}", owner, e.toString());
            return false;
        } finally {
            if (client != null) {
                try { if (client.isConnected()) client.disconnectForcibly(0, 0); } catch (MqttException ignored) { }
                try { client.close(); } catch (MqttException ignored) { }
            }
        }
    }

    String payload(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", UUID.randomUUID().toString());
        fields.put("house", "rack");
        fields.put("kind", "info");
        fields.put("text", text);
        try {
            return json.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Over TLS the broker is a stream behind Vaier's proxy, routed by the name
     * in the handshake, and Paho's own hostname check is kept. The Vaier app
     * swaps it for {@code HttpsURLConnection}'s default verifier, which on
     * Android checks the name — on a desktop JVM that default is a fallback
     * that refuses every host, and the first deploy could not connect.
     */
    static MqttConnectOptions options(String url, String username, String password, boolean cleanSession) {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setCleanSession(cleanSession);
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
