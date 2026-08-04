package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import family.eilertsen.rack.domain.model.SlotId;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

@JsonComponent
public class SlotIdJson {

    public static class Ser extends JsonSerializer<SlotId> {
        @Override
        public void serialize(SlotId id, JsonGenerator g, SerializerProvider s) throws IOException {
            g.writeString(id.value());
        }
    }

    public static class De extends JsonDeserializer<SlotId> {
        @Override
        public SlotId deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return new SlotId(p.getValueAsString());
        }
    }
}
