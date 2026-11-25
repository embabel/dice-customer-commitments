package example.dice.commitments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("live")
class LiveModelConfiguration {

    private final String name;
    private final String decision;

    LiveModelConfiguration(@Value("${commitments.model}") String name,
                           @Value("${commitments.decision}") String decision) {
        this.name = name;
        this.decision = decision;
    }

    String name() {
        return name;
    }

    String decision() {
        return decision;
    }
}
