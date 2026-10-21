package com.david.notification_hub.polling;

import com.david.notification_hub.source.ratelimit.RateLimitInterceptor;
import com.david.notification_hub.source.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(PollingProperties.class)
public class PollingConfig {

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(PollingProperties props) {
        Map<String, RateLimiter> byHost = new HashMap<>();
        props.rateLimits().forEach((host, limit) ->
                byHost.put(host, new RateLimiter(limit.perSecond(), limit.perDay(), Clock.systemUTC())));
        return new RateLimitInterceptor(byHost, d -> Thread.sleep(d.toMillis()));
    }

    /**
     * Puts the limits on every RestClient built from Spring Boot's builder, which is the
     * one the sources are given - so a source is limited without doing anything itself.
     */
    @Bean
    public RestClientCustomizer rateLimitedRestClients(RateLimitInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}
