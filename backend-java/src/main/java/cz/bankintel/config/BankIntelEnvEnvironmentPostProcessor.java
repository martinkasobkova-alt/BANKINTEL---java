package cz.bankintel.config;

import cz.bankintel.util.BankIntelEnvVars;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Propíše {@code dev.local.env} + {@code .env} do Spring Environment (pro {@code @Value}). */
public class BankIntelEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE = "bankintelEnvFiles";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = BankIntelEnvVars.loadAsPropertyMap();
        if (props.isEmpty()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, props));
    }
}
