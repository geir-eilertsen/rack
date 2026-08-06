package family.eilertsen.rack.adapter.out.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import family.eilertsen.rack.domain.model.ProjectId;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

/** So a project id is written "quad-606" and not {"value":"quad-606"}. */
@JsonComponent
public class ProjectIdJson {

    public static class Ser extends JsonSerializer<ProjectId> {
        @Override
        public void serialize(ProjectId id, JsonGenerator g, SerializerProvider s) throws IOException {
            g.writeString(id.value());
        }
    }

    public static class De extends JsonDeserializer<ProjectId> {
        @Override
        public ProjectId deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return new ProjectId(p.getValueAsString());
        }
    }
}
