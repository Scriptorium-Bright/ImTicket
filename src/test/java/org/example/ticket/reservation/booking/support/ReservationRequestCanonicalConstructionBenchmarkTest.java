package org.example.ticket.reservation.booking.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RUN_STRING_CONSTRUCTION_BENCHMARK=true로 명시 실행하는 초기 비교 실험이다.
 * 최종 비교 수치는 src/jmh/java의 JMH benchmark 결과를 사용한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_STRING_CONSTRUCTION_BENCHMARK", matches = "true")
class ReservationRequestCanonicalConstructionBenchmarkTest {

    private static final String SCHEMA = "reservation-pre-reserve:v1";
    private static final Long PERFORMANCE_TIME_ID = 42L;
    private static volatile String blackHole;

    @Test
    void comparesCanonicalConstructionStrategies() {
        List<Strategy> strategies = List.of(
                new Strategy("plus + stream joining", this::canonicalWithPlusAndJoining),
                new Strategy("outer builder + joining", this::canonicalWithOuterBuilderAndJoining),
                new Strategy("single builder", this::canonicalWithSingleBuilder),
                new Strategy("single builder pre-sized", this::canonicalWithPreSizedBuilder)
        );

        System.out.println();
        System.out.println("canonical string construction benchmark");
        System.out.println("timed region excludes sorting, validation and SHA-256");
        System.out.printf("%-10s %-28s %12s %10s%n", "seatIds", "strategy", "ns/op", "vs current");

        for (int seatCount : List.of(1, 4, 16, 128, 1024)) {
            List<Long> seatIds = seatIds(seatCount);
            String expected = canonicalWithPlusAndJoining(PERFORMANCE_TIME_ID, seatIds);

            for (Strategy strategy : strategies) {
                assertThat(strategy.builder().apply(PERFORMANCE_TIME_ID, seatIds))
                        .as("all strategies must produce the same canonical text: %s", strategy.name())
                        .isEqualTo(expected);
            }

            int iterations = iterationsFor(seatCount);
            int warmupIterations = Math.max(1_000, iterations / 5);
            BenchmarkResult baseline = measure(
                    strategies.get(0).builder(), seatIds, warmupIterations, iterations
            );

            System.out.printf("%-10d %-28s %12.1f %9.2fx%n",
                    seatCount, strategies.get(0).name(), baseline.nsPerOperation(), 1.0);

            for (int index = 1; index < strategies.size(); index++) {
                Strategy strategy = strategies.get(index);
                BenchmarkResult result = measure(
                        strategy.builder(), seatIds, warmupIterations, iterations
                );
                System.out.printf("%-10d %-28s %12.1f %9.2fx%n",
                        seatCount,
                        strategy.name(),
                        result.nsPerOperation(),
                        result.nsPerOperation() / baseline.nsPerOperation());
            }
        }

        assertThat(blackHole).isNotNull();
    }

    /** Existing implementation: stream joining first, then outer string concatenation. */
    private String canonicalWithPlusAndJoining(Long performanceTimeId, List<Long> normalizedSeatIds) {
        String seatIds = normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return SCHEMA + "\n"
                + "performanceTimeId=" + performanceTimeId + "\n"
                + "seatIds=" + seatIds;
    }

    /** Only replaces the outer concatenation; joining still creates an intermediate String. */
    private String canonicalWithOuterBuilderAndJoining(Long performanceTimeId, List<Long> normalizedSeatIds) {
        String seatIds = normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return new StringBuilder()
                .append(SCHEMA).append('\n')
                .append("performanceTimeId=").append(performanceTimeId).append('\n')
                .append("seatIds=").append(seatIds)
                .toString();
    }

    /** Builds the complete canonical text with one builder and direct numeric append. */
    private String canonicalWithSingleBuilder(Long performanceTimeId, List<Long> normalizedSeatIds) {
        StringBuilder builder = new StringBuilder();
        appendCanonical(builder, performanceTimeId, normalizedSeatIds);
        return builder.toString();
    }

    /** Same direct builder strategy with enough initial capacity for the benchmark input. */
    private String canonicalWithPreSizedBuilder(Long performanceTimeId, List<Long> normalizedSeatIds) {
        StringBuilder builder = new StringBuilder(64 + normalizedSeatIds.size() * 20);
        appendCanonical(builder, performanceTimeId, normalizedSeatIds);
        return builder.toString();
    }

    private static void appendCanonical(
            StringBuilder builder,
            Long performanceTimeId,
            List<Long> normalizedSeatIds
    ) {
        builder.append(SCHEMA).append('\n')
                .append("performanceTimeId=").append(performanceTimeId).append('\n')
                .append("seatIds=");

        for (int index = 0; index < normalizedSeatIds.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(normalizedSeatIds.get(index));
        }
    }

    private static BenchmarkResult measure(
            BiFunction<Long, List<Long>, String> builder,
            List<Long> seatIds,
            int warmupIterations,
            int measurementIterations
    ) {
        for (int index = 0; index < warmupIterations; index++) {
            blackHole = builder.apply(PERFORMANCE_TIME_ID, seatIds);
        }

        long[] samples = new long[5];
        for (int sample = 0; sample < samples.length; sample++) {
            long startedAt = System.nanoTime();
            for (int index = 0; index < measurementIterations; index++) {
                blackHole = builder.apply(PERFORMANCE_TIME_ID, seatIds);
            }
            samples[sample] = System.nanoTime() - startedAt;
        }

        Arrays.sort(samples);
        return new BenchmarkResult(samples[samples.length / 2], measurementIterations);
    }

    private static int iterationsFor(int seatCount) {
        if (seatCount <= 16) {
            return 100_000;
        }
        if (seatCount <= 128) {
            return 20_000;
        }
        return 2_000;
    }

    private static List<Long> seatIds(int count) {
        List<Long> seatIds = new ArrayList<>(count);
        for (long index = 0; index < count; index++) {
            seatIds.add(100_000L + index);
        }
        return List.copyOf(seatIds);
    }

    private record Strategy(
            String name,
            BiFunction<Long, List<Long>, String> builder
    ) {
    }

    private record BenchmarkResult(long totalNanos, int operations) {
        double nsPerOperation() {
            return (double) totalNanos / operations;
        }
    }
}
