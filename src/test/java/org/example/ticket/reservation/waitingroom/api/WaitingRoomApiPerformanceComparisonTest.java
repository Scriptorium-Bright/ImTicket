package org.example.ticket.reservation.waitingroom.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.controller.WaitingRoomController;
import org.example.ticket.reservation.waitingroom.pass.HmacWaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;
import org.example.ticket.reservation.waitingroom.repository.inmemory.InMemoryWaitingRoomStore;
import org.example.ticket.reservation.waitingroom.repository.redis.RedisWaitingRoomStore;
import org.example.ticket.reservation.waitingroom.repository.redis.WaitingRoomKeyFactory;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomFeaturePolicy;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 embedded Tomcat HTTP 경로에서 메모리·Redis Waiting Room의 API 지연과 동시 처리량을 비교한다.
 * 기본 실행은 메모리만 측정하고 Redis 비교는 전용 Redis를 띄운 뒤 환경 변수로 활성화한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitingRoomApiPerformanceComparisonTest {

    private static final int USER_COUNT = 2_000;
    private static final int CLIENT_WORKERS = 32;
    private static final int TOMCAT_MAX_THREADS = 32;
    private static final long PERFORMANCE_TIME_ID = 9_201L;
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final Duration WAITING_TTL = Duration.ofMinutes(30);
    private static final Duration ENTRY_LEASE = Duration.ofMinutes(5);
    private static final Duration RETENTION = Duration.ofHours(1);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> memberIds = memberIds();
    private ExecutorService clientExecutor;

    /** 메모리 저장소를 embedded Tomcat API에 연결해 2,000명 join·status 기준선을 출력한다. */
    @Test
    void measuresMemoryStoreThroughEmbeddedTomcatApi() throws Exception {
        ApiReport report = runApi("memory", PERFORMANCE_TIME_ID);
        assertThat(report.join().count()).isEqualTo(USER_COUNT);
        assertThat(report.status().count()).isEqualTo(USER_COUNT);
        printReport(report);
    }

    /** 전용 Redis가 켜진 경우 같은 embedded Tomcat API workload를 메모리와 비교한다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "WAITING_ROOM_REDIS_BENCHMARK", matches = "(?i)true")
    void comparesMemoryAndRedisThroughEmbeddedTomcatApi() throws Exception {
        ApiReport memory = runApi("memory", PERFORMANCE_TIME_ID + 1);
        ApiReport redis = runApi("redis", PERFORMANCE_TIME_ID + 2);
        printReport(memory, redis);
        assertThat(redis.join().count()).isEqualTo(USER_COUNT);
        assertThat(redis.status().count()).isEqualTo(USER_COUNT);
    }

    /** 테스트에서 생성한 client executor를 종료한다. */
    @AfterAll
    void shutdownClientExecutor() {
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
    }

    private ApiReport runApi(String storeType, long performanceTimeId) throws Exception {
        String metricsId = storeType + "-" + UUID.randomUUID();
        try (ConfigurableApplicationContext context = startContext(storeType, metricsId)) {
            if ("redis".equals(storeType)) {
                context.getBean(StringRedisTemplate.class)
                        .getConnectionFactory()
                        .getConnection()
                        .serverCommands()
                        .flushDb();
            }
            assertThat(context.getBeansOfType(WaitingRoomController.class)).hasSize(1);
            assertThat(context.getBean(WaitingRoomProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(WaitingRoomProperties.class).getEnabledPerformanceTimeIds())
                    .contains(performanceTimeId);
            assertThat(context.getBean(WaitingRoomService.class).requiresWaitingRoom(performanceTimeId)).isTrue();
            int port = ((org.springframework.boot.web.context.WebServerApplicationContext) context)
                    .getWebServer()
                    .getPort();
            RequestMetrics serverMetrics = context.getBean(RequestMetrics.class);
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String baseUrl = "http://127.0.0.1:" + port;
            AtomicReferenceArray<TicketRef> tickets = new AtomicReferenceArray<>(USER_COUNT);

            HttpMetric join = measureHttp(USER_COUNT, serverMetrics, index -> {
                HttpResponse<String> response = sendJoin(client, baseUrl, performanceTimeId, memberIds.get(index));
                tickets.set(index, parseTicket(response, memberIds.get(index)));
            });
            for (int index = 0; index < USER_COUNT; index++) {
                assertThat(tickets.get(index)).as("join ticket %s", index).isNotNull();
            }
            HttpMetric status = measureHttp(USER_COUNT, serverMetrics, index -> {
                TicketRef ticket = tickets.get(index);
                sendStatus(client, baseUrl, performanceTimeId, ticket);
            });
            return new ApiReport(storeType, join, status, TOMCAT_MAX_THREADS);
        }
    }

    private ConfigurableApplicationContext startContext(String storeType, String metricsId) {
        return new SpringApplicationBuilder(TestApplication.class)
                .properties(
                        "server.port=0",
                        "server.tomcat.threads.max=" + TOMCAT_MAX_THREADS,
                        "server.tomcat.threads.min-spare=4",
                        "server.tomcat.max-connections=2000",
                        "server.tomcat.accept-count=2000",
                        "ticket.application.role=waiting-room",
                        "test.waiting-room.store=" + storeType,
                        "test.waiting-room.metrics-id=" + metricsId,
                        "spring.config.location=optional:classpath:/waiting-room-api-test.properties",
                        "reservation.waiting-room.enabled=true",
                        "spring.main.banner-mode=off",
                        "spring.main.web-application-type=servlet",
                        "logging.level.root=ERROR"
                )
                .run();
    }

    private HttpMetric measureHttp(int operationCount, RequestMetrics serverMetrics, IndexedOperation operation)
            throws InterruptedException, ExecutionException {
        clientExecutor = Executors.newFixedThreadPool(CLIENT_WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        int workerCount = Math.min(CLIENT_WORKERS, operationCount);
        List<Future<?>> futures = new ArrayList<>(workerCount);
        List<Long> samples = Collections.synchronizedList(new ArrayList<>(operationCount));
        AtomicInteger clientInFlight = new AtomicInteger();
        AtomicInteger clientMaxInFlight = new AtomicInteger();
        serverMetrics.reset();
        try {
            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                int assignedWorker = workerIndex;
                futures.add(clientExecutor.submit(() -> {
                    start.await();
                    for (int index = assignedWorker; index < operationCount; index += workerCount) {
                        int active = clientInFlight.incrementAndGet();
                        clientMaxInFlight.accumulateAndGet(active, Math::max);
                        long operationStart = System.nanoTime();
                        try {
                            operation.run(index);
                            samples.add(System.nanoTime() - operationStart);
                        } finally {
                            clientInFlight.decrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            long wallStart = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            return new HttpMetric(
                    operationCount,
                    System.nanoTime() - wallStart,
                    samples,
                    clientMaxInFlight.get(),
                    serverMetrics.maxInFlight(),
                    serverMetrics.successCount()
            );
        } finally {
            clientExecutor.shutdownNow();
        }
    }

    private HttpResponse<String> sendJoin(
            HttpClient client,
            String baseUrl,
            long performanceTimeId,
            long memberId
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/reservation/waiting-room/" + performanceTimeId + "/join"))
                .header(HttpHeaders.ACCEPT, "application/json")
                .header("X-Test-Member-Id", Long.toString(memberId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("join status").isEqualTo(200);
        return response;
    }

    private void sendStatus(
            HttpClient client,
            String baseUrl,
            long performanceTimeId,
            TicketRef ticket
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/reservation/waiting-room/"
                        + performanceTimeId + "/tickets/" + ticket.ticketId()))
                .header(HttpHeaders.ACCEPT, "application/json")
                .header("X-Test-Member-Id", Long.toString(ticket.memberId()))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("status response").isEqualTo(200);
    }

    private TicketRef parseTicket(HttpResponse<String> response, long memberId) throws IOException {
        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.path("success").asBoolean()).isTrue();
        UUID ticketId = UUID.fromString(root.path("data").path("ticketId").asText());
        assertThat(root.path("data").path("position").asLong()).isGreaterThan(0L);
        return new TicketRef(ticketId, memberId);
    }

    private void printReport(ApiReport... reports) {
        System.out.println("[Waiting Room API comparison: users=" + USER_COUNT
                + ", clientWorkers=" + CLIENT_WORKERS + ", tomcatMaxThreads=" + TOMCAT_MAX_THREADS + "]");
        System.out.println("| store | operation | count | p50 ms | p95 ms | throughput req/s | client in-flight max | server in-flight max | HTTP 2xx |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (ApiReport report : reports) {
            printMetric(report.store(), "join", report.join());
            printMetric(report.store(), "status", report.status());
        }
    }

    private void printMetric(String store, String operation, HttpMetric metric) {
        System.out.printf(
                "| %s | %s | %d | %.3f | %.3f | %.2f | %d | %d | %d |%n",
                store,
                operation,
                metric.count(),
                metric.percentileMillis(0.50),
                metric.percentileMillis(0.95),
                metric.throughput(),
                metric.clientMaxInFlight(),
                metric.serverMaxInFlight(),
                metric.successCount()
        );
    }

    private List<Long> memberIds() {
        List<Long> ids = new ArrayList<>(USER_COUNT);
        for (int index = 0; index < USER_COUNT; index++) {
            ids.add(200_000L + index);
        }
        return Collections.unmodifiableList(ids);
    }

    @FunctionalInterface
    private interface IndexedOperation {
        void run(int index) throws Exception;
    }

    private record TicketRef(UUID ticketId, long memberId) {
    }

    private record ApiReport(String store, HttpMetric join, HttpMetric status, int tomcatMaxThreads) {
    }

    private record HttpMetric(
            int count,
            long wallNanos,
            List<Long> samples,
            int clientMaxInFlight,
            int serverMaxInFlight,
            int successCount
    ) {
        private HttpMetric {
            samples = new ArrayList<>(samples);
            samples.sort(Comparator.naturalOrder());
        }

        private double percentileMillis(double percentile) {
            int index = (int) Math.ceil(percentile * samples.size()) - 1;
            return samples.get(Math.max(0, Math.min(index, samples.size() - 1))) / 1_000_000.0;
        }

        private double throughput() {
            return count / (wallNanos / 1_000_000_000.0);
        }
    }

    /** API handler 실행 중 동시 요청 수를 측정하는 테스트 전용 filter state다. */
    static final class RequestMetrics {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger successCount = new AtomicInteger();

        private void reset() {
            inFlight.set(0);
            maxInFlight.set(0);
            successCount.set(0);
        }

        private void enter() {
            int active = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(active, Math::max);
        }

        private void exit(int status) {
            if (status >= 200 && status < 300) {
                successCount.incrementAndGet();
            }
            inFlight.decrementAndGet();
        }

        private int maxInFlight() {
            return maxInFlight.get();
        }

        private int successCount() {
            return successCount.get();
        }
    }

    /** 저장소와 embedded Tomcat만 올리는 API 비교용 Spring Boot context다. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class
    })
    static class TestApplication {

        @Bean
        WaitingRoomProperties waitingRoomProperties() {
            WaitingRoomProperties properties = new WaitingRoomProperties();
            properties.setEnabled(true);
            properties.setEnabledPerformanceTimeIds(Set.of(
                    PERFORMANCE_TIME_ID,
                    PERFORMANCE_TIME_ID + 1,
                    PERFORMANCE_TIME_ID + 2
            ));
            properties.setMaxWaitingTickets(USER_COUNT + 10);
            properties.setMaxActiveSessions(USER_COUNT);
            properties.setAdmitPerInterval(USER_COUNT);
            properties.setWaitingTicketTtl(WAITING_TTL);
            properties.setEntryLease(ENTRY_LEASE);
            properties.setTerminalRetention(RETENTION);
            properties.setPromotionInterval(Duration.ofSeconds(1));
            properties.setStatusPollAfter(Duration.ofSeconds(2));
            return properties;
        }

        @Bean
        WaitingRoomTimePolicy waitingRoomTimePolicy(WaitingRoomProperties properties) {
            return new WaitingRoomTimePolicy(Clock.fixed(NOW, ZoneOffset.UTC), properties);
        }

        @Bean
        WaitingRoomFeaturePolicy waitingRoomFeaturePolicy(WaitingRoomProperties properties) {
            return performanceTimeId -> properties.isEnabled()
                    && properties.getEnabledPerformanceTimeIds().contains(performanceTimeId);
        }

        @Bean
        HmacWaitingRoomPassCodec waitingRoomPassCodec() {
            return new HmacWaitingRoomPassCodec("api-performance-test-secret");
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        WaitingRoomKeyFactory waitingRoomKeyFactory() {
            return new WaitingRoomKeyFactory();
        }

        @Bean
        @ConditionalOnProperty(name = "test.waiting-room.store", havingValue = "memory", matchIfMissing = true)
        WaitingRoomStore memoryWaitingRoomStore() {
            return new InMemoryWaitingRoomStore();
        }

        @Bean
        @ConditionalOnProperty(name = "test.waiting-room.store", havingValue = "redis")
        LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(
                    environment("WAITING_ROOM_REDIS_HOST", "127.0.0.1"),
                    Integer.parseInt(environment("WAITING_ROOM_REDIS_PORT", "16380"))
            );
        }

        @Bean
        @ConditionalOnProperty(name = "test.waiting-room.store", havingValue = "redis")
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean
        @ConditionalOnProperty(name = "test.waiting-room.store", havingValue = "redis")
        WaitingRoomStore redisWaitingRoomStore(
                StringRedisTemplate redisTemplate,
                WaitingRoomKeyFactory keyFactory
        ) {
            return new RedisWaitingRoomStore(redisTemplate, keyFactory);
        }

        @Bean
        WaitingRoomService waitingRoomService(
                WaitingRoomStore store,
                WaitingRoomProperties properties,
                WaitingRoomTimePolicy timePolicy,
                WaitingRoomFeaturePolicy featurePolicy,
                HmacWaitingRoomPassCodec passCodec,
                MeterRegistry meterRegistry,
                org.springframework.context.ApplicationEventPublisher eventPublisher
        ) {
            return new WaitingRoomService(
                    store,
                    properties,
                    timePolicy,
                    featurePolicy,
                    passCodec,
                    meterRegistry,
                    eventPublisher
            );
        }

        @Bean
        WaitingRoomController waitingRoomController(WaitingRoomService waitingRoomService) {
            return new WaitingRoomController(waitingRoomService);
        }

        @Bean
        RequestMetrics requestMetrics() {
            return new RequestMetrics();
        }

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> requestMetricsFilter(RequestMetrics metrics) {
            OncePerRequestFilter filter = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                        jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response,
                        jakarta.servlet.FilterChain filterChain
                ) throws jakarta.servlet.ServletException, IOException {
                    metrics.enter();
                    try {
                        filterChain.doFilter(request, response);
                    } finally {
                        metrics.exit(response.getStatus());
                    }
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/api/reservation/waiting-room/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        @Bean
        WebMvcConfigurer principalResolver() {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new HandlerMethodArgumentResolver() {
                        @Override
                        public boolean supportsParameter(MethodParameter parameter) {
                            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                        }

                        @Override
                        public Object resolveArgument(
                                MethodParameter parameter,
                                ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest,
                                WebDataBinderFactory binderFactory
                        ) {
                            String memberId = webRequest.getHeader("X-Test-Member-Id");
                            if (memberId == null || memberId.isBlank()) {
                                throw new IllegalArgumentException("X-Test-Member-Id is required");
                            }
                            return new MetamaskUserDetails(Member.builder()
                                    .id(Long.parseLong(memberId))
                                    .walletAddress("0xapi-test-" + memberId)
                                    .role("USER")
                                    .build());
                        }
                    });
                }
            };
        }

        private static String environment(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }
}
