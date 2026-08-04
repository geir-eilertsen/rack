package family.eilertsen.rack.application;

import family.eilertsen.rack.domain.model.Container;
import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.ContainerLayout;
import family.eilertsen.rack.domain.model.SlotId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(RackProperties.class)
public class RackConfiguration {

    @Bean
    ContainerRegistry containerRegistry(RackProperties props) {
        List<Container> containers = new ArrayList<>();
        List<RackProperties.ContainerConfig> configs = props.containers() == null ? List.of() : props.containers();
        for (RackProperties.ContainerConfig cc : configs) {
            ContainerId id = new ContainerId(cc.id());
            List<SlotId> slots = expandLayout(cc.layout());
            float scale = cc.labelScale() == null ? 1.0f : cc.labelScale();
            containers.add(new Container(id, cc.name(), slots, scale));
        }
        return new ContainerRegistry(containers);
    }

    private static List<SlotId> expandLayout(RackProperties.LayoutConfig layout) {
        return switch (layout.kind()) {
            case "grid" -> ContainerLayout.grid(layout.cols(), layout.rows());
            case "linear" -> ContainerLayout.linear(layout.count(), layout.prefix());
            default -> throw new IllegalArgumentException("Unknown layout kind: " + layout.kind());
        };
    }
}
