package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import family.eilertsen.rack.domain.model.DrawerId;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

@JsonComponent
public class DrawerIdJson {

    public static class Ser extends JsonSerializer<DrawerId> {
        @Override
        public void serialize(DrawerId id, JsonGenerator g, SerializerProvider s) throws IOException {
            g.writeString(id.value());
        }
    }

    public static class De extends JsonDeserializer<DrawerId> {
        @Override
        public DrawerId deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return new DrawerId(p.getValueAsString());
        }
    }
}
