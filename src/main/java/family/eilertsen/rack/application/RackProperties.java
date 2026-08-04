package family.eilertsen.rack.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("rack")
public record RackProperties(
    String dataDir,
    List<ContainerConfig> containers
) {
    public record ContainerConfig(
        String id,
        String name,
        LayoutConfig layout,
        Float labelScale
    ) {}

    public record LayoutConfig(
        String kind,
        Integer cols,
        Integer rows,
        Integer count,
        String prefix
    ) {}
}
