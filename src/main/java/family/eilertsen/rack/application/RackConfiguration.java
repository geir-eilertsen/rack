package family.eilertsen.rack.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RackProperties.class, ModelPrices.class})
public class RackConfiguration {
}
