package org.example.ticket.reservation.waitingroom.sse;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** application instance가 보유한 Waiting Room SSE emitter를 ticket 단위로 관리한다. */
@Component
@RequiredArgsConstructor
public class WaitingRoomSseEmitterRegistry {

    private final WaitingRoomProperties properties;
    @Qualifier("waitingRoomSseTaskExecutor")
    private final TaskExecutor waitingRoomSseTaskExecutor;
    private final MeterRegistry meterRegistry;
    private final Map<WaitingRoomSseTicketKey, TicketConnections> connections = new ConcurrentHashMap<>();

    /** active connection과 registry size를 low-cardinality gauge로 등록한다.
     * Prometheus가 emitter 누수와 연결 규모를 관찰할 수 있게 한다. */
    @PostConstruct
    void registerGauges() {
        Gauge.builder("imticket.waiting-room.sse.active-connections", this, WaitingRoomSseEmitterRegistry::connectionCount)
                .register(meterRegistry);
        Gauge.builder("imticket.waiting-room.sse.registry-size", this, WaitingRoomSseEmitterRegistry::ticketCount)
                .register(meterRegistry);
    }

    /** owner 검증을 마친 browser connection을 ticket registry에 등록한다.
     * ticket별 connection 상한을 적용해 emitter 수를 제한한다. */
    public WaitingRoomSseConnection register(long performanceTimeId, UUID ticketId, long memberId) {
        WaitingRoomSseTicketKey key = new WaitingRoomSseTicketKey(performanceTimeId, ticketId);
        TicketConnections byTicket = connections.computeIfAbsent(key, ignored -> new TicketConnections());
        WaitingRoomSseConnection connection;
        synchronized (byTicket) {
            if (byTicket.connections.size() >= properties.getSseMaxConnectionsPerTicket()) {
                throw new WaitingRoomSseConnectionLimitException();
            }
            SseEmitter emitter = new SseEmitter(properties.getSseConnectionTimeout().toMillis());
            connection = new WaitingRoomSseConnection(UUID.randomUUID(), key, memberId, emitter);
            byTicket.connections.put(connection.connectionId(), connection);
            emitter.onCompletion(() -> release(key, connection, "completion"));
            emitter.onTimeout(() -> release(key, connection, "timeout"));
            emitter.onError(error -> release(key, connection, "write_error"));
        }
        meterRegistry.counter("imticket.waiting-room.sse.connections", "result", "opened").increment();
        return connection;
    }

    /** registry 등록 뒤 최신 snapshot을 첫 event로 넣고 event 전달을 시작한다.
     * 초기화 중 lifecycle event가 유실되지 않도록 pending queue를 사용한다. */
    public void initialize(WaitingRoomSseConnection connection, Supplier<WaitingRoomStatusResponse> snapshotSupplier) {
        boolean submit = false;
        synchronized (connection) {
            requireOpen(connection);
            WaitingRoomStatusResponse snapshot = snapshotSupplier.get();
            connection.pendingEvents().addLast(new WaitingRoomSseEvent("snapshot", snapshot, false, Instant.now()));
            connection.ready(true);
            if (!connection.draining()) {
                connection.draining(true);
                submit = true;
            }
        }
        if (submit) {
            submit(connection);
        }
    }

    /** lifecycle event를 해당 ticket의 local connection에만 전달한다.
     * terminal 상태는 event 전송 뒤 connection을 종료한다. */
    public void publish(
            long performanceTimeId,
            UUID ticketId,
            WaitingRoomStatusResponse snapshot,
            Instant occurredAt
    ) {
        String eventType = snapshot.status().isTerminal()
                ? "terminal"
                : snapshot.status() == org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus.ADMITTED
                ? "admitted"
                : "snapshot";
        boolean closeAfterDelivery = snapshot.status().isTerminal();
        TicketConnections byTicket = connections.get(new WaitingRoomSseTicketKey(performanceTimeId, ticketId));
        if (byTicket == null) {
            return;
        }
        List<WaitingRoomSseConnection> localConnections;
        synchronized (byTicket) {
            localConnections = new ArrayList<>(byTicket.connections.values());
        }
        for (WaitingRoomSseConnection connection : localConnections) {
            enqueue(connection, new WaitingRoomSseEvent(eventType, snapshot, closeAfterDelivery, occurredAt));
        }
    }

    /** reverse proxy idle timeout보다 짧은 주기로 연결 생존 event를 보낸다.
     * 연결이 정상적으로 유지되고 있는지 client가 판단할 수 있게 한다. */
    public void publishKeepalive() {
        for (TicketConnections byTicket : connections.values()) {
            List<WaitingRoomSseConnection> localConnections;
            synchronized (byTicket) {
                localConnections = new ArrayList<>(byTicket.connections.values());
            }
            for (WaitingRoomSseConnection connection : localConnections) {
                Instant now = Instant.now();
                enqueue(connection, new WaitingRoomSseEvent("keepalive", Map.of("at", now.toString()), false, now));
            }
        }
    }

    /** Pub/Sub subscriber가 member ID와 ticket ID로 local connection을 찾는다.
     * 해당 application instance에 연결이 없으면 빈 list를 반환한다. */
    public List<WaitingRoomSseConnection> find(long performanceTimeId, UUID ticketId) {
        TicketConnections byTicket = connections.get(new WaitingRoomSseTicketKey(performanceTimeId, ticketId));
        if (byTicket == null) {
            return List.of();
        }
        synchronized (byTicket) {
            return List.copyOf(byTicket.connections.values());
        }
    }

    /** event 전달 뒤 terminal ticket connection을 종료한다.
     * container 종료와 client disconnect 경합을 안전하게 처리한다. */
    public void close(WaitingRoomSseConnection connection, String reason) {
        if (release(connection.ticketKey(), connection, reason)) {
            try {
                connection.emitter().complete();
            } catch (RuntimeException ignored) {
                // Client disconnect와 terminal event 전송이 경합하면 container가 이미 async request를 종료한다.
            }
        }
    }

    /** 현재 연결 수를 Micrometer gauge와 부하 결과에서 사용한다.
     * 모든 ticket group의 connection 수를 합산한다. */
    public int connectionCount() {
        return connections.values().stream().mapToInt(byTicket -> {
            synchronized (byTicket) {
                return byTicket.connections.size();
            }
        }).sum();
    }

    /** local registry가 보유한 ticket key 개수를 반환한다.
     * SSE registry size gauge가 연결 그룹 수를 관찰할 때 사용한다. */
    private int ticketCount() {
        return connections.size();
    }

    /** connection의 pending event queue에 event를 추가한다.
     * per-connection 상한과 직렬 drain 상태를 함께 적용한다. */
    private void enqueue(WaitingRoomSseConnection connection, WaitingRoomSseEvent event) {
        boolean submit = false;
        boolean pendingLimitReached = false;
        synchronized (connection) {
            if (connection.closed() || !connection.ready()) {
                return;
            }
            if (connection.pendingEvents().size() >= properties.getSseMaxPendingWritesPerConnection()) {
                pendingLimitReached = true;
            } else {
                connection.pendingEvents().addLast(event);
                if (!connection.draining()) {
                    connection.draining(true);
                    submit = true;
                }
            }
        }
        if (pendingLimitReached) {
            close(connection, "pending_limit");
            return;
        }
        if (submit) {
            submit(connection);
        }
    }

    /** connection drain task를 bounded SSE executor에 제출한다.
     * executor 거절은 connection 종료로 처리한다. */
    private void submit(WaitingRoomSseConnection connection) {
        try {
            waitingRoomSseTaskExecutor.execute(() -> drain(connection));
        } catch (RejectedExecutionException exception) {
            close(connection, "executor_rejected");
        }
    }

    /** pending event를 순서대로 emitter에 전송한다.
     * terminal event 전송 뒤 connection을 종료한다. */
    private void drain(WaitingRoomSseConnection connection) {
        while (true) {
            WaitingRoomSseEvent event;
            synchronized (connection) {
                if (connection.closed()) {
                    connection.draining(false);
                    return;
                }
                event = connection.pendingEvents().pollFirst();
                if (event == null) {
                    connection.draining(false);
                    return;
                }
            }
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                connection.emitter().send(SseEmitter.event().name(event.type()).data(event.data()));
                sample.stop(meterRegistry.timer("imticket.waiting-room.sse.write-duration", "type", event.type()));
                java.time.Duration deliveryLatency = java.time.Duration.between(event.occurredAt(), Instant.now());
                if (!deliveryLatency.isNegative()) {
                    meterRegistry.timer("imticket.waiting-room.sse.delivery-latency", "type", event.type())
                            .record(deliveryLatency);
                }
                meterRegistry.counter("imticket.waiting-room.sse.events", "type", event.type(), "result", "sent").increment();
            } catch (IOException | IllegalStateException exception) {
                sample.stop(meterRegistry.timer("imticket.waiting-room.sse.write-duration", "type", event.type()));
                meterRegistry.counter("imticket.waiting-room.sse.events", "type", event.type(), "result", "failure").increment();
                close(connection, "write_error");
                return;
            }
            if (event.closeAfterDelivery()) {
                close(connection, "terminal");
                return;
            }
        }
    }

    /** registry에서 connection을 제거하고 종료 metric을 기록한다.
     * 같은 ticket의 마지막 connection이면 ticket group도 제거한다. */
    private boolean release(WaitingRoomSseTicketKey key, WaitingRoomSseConnection connection, String reason) {
        TicketConnections byTicket = connections.get(key);
        if (byTicket == null) {
            return false;
        }
        boolean released;
        synchronized (byTicket) {
            released = byTicket.connections.remove(connection.connectionId(), connection);
            if (released) {
                synchronized (connection) {
                    connection.close();
                }
            }
            if (byTicket.connections.isEmpty()) {
                connections.remove(key, byTicket);
            }
        }
        if (released) {
            meterRegistry.counter("imticket.waiting-room.sse.emitters.closed", "reason", reason).increment();
        }
        return released;
    }

    /** connection이 아직 종료되지 않았는지 확인한다.
     * 종료된 connection 초기화는 명시적 상태 오류로 처리한다. */
    private void requireOpen(WaitingRoomSseConnection connection) {
        if (connection.closed()) {
            throw new IllegalStateException("SSE connection is closed");
        }
    }

    private static final class TicketConnections {
        private final Map<UUID, WaitingRoomSseConnection> connections = new ConcurrentHashMap<>();
    }
}
