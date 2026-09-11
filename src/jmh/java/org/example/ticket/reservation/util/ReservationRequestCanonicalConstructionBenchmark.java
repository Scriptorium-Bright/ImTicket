package org.example.ticket.reservation.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * JMH comparison for canonical request text construction only.
 * Sorting, validation and SHA-256 are intentionally outside this benchmark.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class ReservationRequestCanonicalConstructionBenchmark {

    private static final String SCHEMA = "reservation-pre-reserve:v1";
    private static final Long PERFORMANCE_TIME_ID = 42L;

    @Param({"1", "4", "16", "128", "1024"})
    private int seatCount;

    private List<Long> normalizedSeatIds;

    @Setup(Level.Trial)
    public void setUp() {
        List<Long> seatIds = new ArrayList<>(seatCount);
        for (long index = 0; index < seatCount; index++) {
            seatIds.add(100_000L + index);
        }
        normalizedSeatIds = List.copyOf(seatIds);
    }

    @Benchmark
    public String plusAndJoining() {
        String seatIds = normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return SCHEMA + "\n"
                + "performanceTimeId=" + PERFORMANCE_TIME_ID + "\n"
                + "seatIds=" + seatIds;
    }

    @Benchmark
    public String outerBuilderAndJoining() {
        String seatIds = normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return new StringBuilder()
                .append(SCHEMA).append('\n')
                .append("performanceTimeId=").append(PERFORMANCE_TIME_ID).append('\n')
                .append("seatIds=").append(seatIds)
                .toString();
    }

    @Benchmark
    public String singleBuilder() {
        StringBuilder builder = new StringBuilder();
        appendCanonical(builder);
        return builder.toString();
    }

    @Benchmark
    public String singleBuilderPreSized() {
        StringBuilder builder = new StringBuilder(64 + normalizedSeatIds.size() * 20);
        appendCanonical(builder);
        return builder.toString();
    }

    private void appendCanonical(StringBuilder builder) {
        builder.append(SCHEMA).append('\n')
                .append("performanceTimeId=").append(PERFORMANCE_TIME_ID).append('\n')
                .append("seatIds=");

        for (int index = 0; index < normalizedSeatIds.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(normalizedSeatIds.get(index));
        }
    }
}
