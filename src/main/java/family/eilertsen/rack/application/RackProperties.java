package family.eilertsen.rack.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rack")
public record RackProperties(
    String dataDir
) {}
