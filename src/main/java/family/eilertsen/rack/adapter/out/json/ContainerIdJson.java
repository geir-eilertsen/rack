package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import family.eilertsen.rack.domain.model.ContainerId;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

@JsonComponent
public class ContainerIdJson {

    public static class Ser extends JsonSerializer<ContainerId> {
        @Override
        public void serialize(ContainerId id, JsonGenerator g, SerializerProvider s) throws IOException {
            g.writeString(id.value());
        }
    }

    public static class De extends JsonDeserializer<ContainerId> {
        @Override
        public ContainerId deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return new ContainerId(p.getValueAsString());
        }
    }
}
