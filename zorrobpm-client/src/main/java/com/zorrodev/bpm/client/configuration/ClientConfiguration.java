package com.zorrodev.bpm.client.configuration;

import com.zorrodev.bpm.client.*;
import com.zorrodev.bpm.client.resolver.ProcessDefinitionQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.ProcessInstanceQueryParameterArgumentResolver;
import com.zorrodev.bpm.client.resolver.ServiceTaskQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.UserTaskQueryParametersArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

@Configuration
@PropertySource("classpath:zorrobpm-client.properties")
public class ClientConfiguration {

    @Value("${app.m11s.zorrodev.bpm.url}")
    private String baseUrl;

    /**
     * WO-API-2: connect timeout default. No precedent anywhere in the project
     * (grep over *.java/*.properties/*.yml is empty), so the default is chosen
     * from first principles: connecting to a live host is milliseconds; 5s
     * only bites on black-holed routes, where failing fast is the point.
     */
    @Value("${zbpm.client.connect-timeout:5s}")
    private String connectTimeoutValue;

    /**
     * WO-API-2: read timeout default. Same story — no in-project precedent.
     * 30s bounds a hung server while staying far above any healthy endpoint
     * latency in this codebase (all read paths are single bounded queries).
     */
    @Value("${zbpm.client.read-timeout:30s}")
    private String readTimeoutValue;

    /**
     * WO-API-2: default credential provider — anonymous (no Authorization
     * header), preserving the pre-WO-API-2 behaviour. Any user-defined
     * {@link ClientCredentialsProvider} bean replaces this one; the interceptor
     * below queries the provider on every request, so rotation applies without
     * rebuilding any client.
     */
    @Bean
    @ConditionalOnMissingBean(ClientCredentialsProvider.class)
    public ClientCredentialsProvider clientCredentialsProvider() {
        return () -> null;
    }

    /**
     * WO-API-2: the single shared RestClient all SDK proxies are built from.
     * Users with a fully custom setup define their own {@code RestClient} bean —
     * this one backs off, and (by construction below) the client proxies keep
     * working: they are built from this factory method, not from the bean.
     */
    @Bean
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient zbpmRestClient(ClientCredentialsProvider credentials) {
        return newRestClient(credentials);
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeClient.class)
    public RuntimeClient runtimeClient(ClientCredentialsProvider credentials) {
        RestClientAdapter adapter = RestClientAdapter.create(newRestClient(credentials));
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter)
            .build();

        return factory.createClient(RuntimeClient.class);
    }

    @Bean
    @ConditionalOnMissingBean(ProcessDefinitionClient.class)
    public ProcessDefinitionClient processDefinitionClient(ClientCredentialsProvider credentials) {
        RestClientAdapter adapter = RestClientAdapter.create(newRestClient(credentials));
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter)
                .customArgumentResolver(new ProcessDefinitionQueryParametersArgumentResolver())
                .build();

        return factory.createClient(ProcessDefinitionClient.class);
    }

    @Bean
    @ConditionalOnMissingBean(QueryClient.class)
    public QueryClient queryClient(ClientCredentialsProvider credentials) {
        RestClientAdapter adapter = RestClientAdapter.create(newRestClient(credentials));
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter)
            .customArgumentResolver(new ProcessDefinitionQueryParametersArgumentResolver())
            .customArgumentResolver(new ProcessInstanceQueryParameterArgumentResolver())
            .customArgumentResolver(new ServiceTaskQueryParametersArgumentResolver())
            .customArgumentResolver(new UserTaskQueryParametersArgumentResolver())
            .build();

        return factory.createClient(QueryClient.class);
    }

    private RestClient newRestClient(ClientCredentialsProvider credentials) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(parseDuration(connectTimeoutValue, "zbpm.client.connect-timeout"));
        requestFactory.setReadTimeout(parseDuration(readTimeoutValue, "zbpm.client.read-timeout"));
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .requestInterceptor((request, body, execution) -> {
                String token = credentials.currentToken();
                if (token != null && !token.isBlank()) {
                    request.getHeaders().setBearerAuth(token);
                }
                return execution.execute(request, body);
            })
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    /**
     * Parses a timeout property without depending on Boot's conversion service
     * (this configuration must also boot in a plain Spring context, where
     * {@code Duration}-typed {@code @Value} fields have no converter).
     * Accepts Boot-style suffixes ({@code 500ms}, {@code 5s}, {@code 1m}…)
     * and ISO-8601 ({@code PT30S}). Fail-fast on garbage — a mistyped timeout
     * must break startup, not silently become infinite.
     */
    public static Duration parseDuration(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
        String text = value.trim();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("^(\\d+)(ns|us|ms|s|m|h|d)$").matcher(text);
        if (m.matches()) {
            long amount = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ns" -> Duration.ofNanos(amount);
                case "us" -> Duration.of(amount, java.time.temporal.ChronoUnit.MICROS);
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("unreachable");
            };
        }
        try {
            return Duration.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                property + "='" + value + "': expected e.g. 500ms, 5s, 30s or PT30S", e);
        }
    }
}
