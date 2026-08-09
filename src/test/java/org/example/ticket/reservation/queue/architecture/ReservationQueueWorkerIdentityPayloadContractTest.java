package org.example.ticket.reservation.queue.architecture;

import io.jsonwebtoken.Claims;
import org.example.ticket.security.jwt.JwtUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationQueueWorkerIdentityPayloadContractTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void signedJwtCarriesMemberId() throws Exception {
        Method createJwt = JwtUtil.class.getMethod(
                "createJwt",
                long.class,
                String.class,
                String.class
        );
        Method getMemberId = JwtUtil.class.getMethod("getMemberId", Claims.class);
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);

        String token = (String) createJwt.invoke(jwtUtil, 42L, "0xowner", "ROLE_USER");
        Claims claims = jwtUtil.parseClaims(token);

        assertThat(getMemberId.invoke(jwtUtil, claims)).isEqualTo(42L);
    }

    @Test
    void queueDefinesVersionedWorkerPayload() throws Exception {
        Class<?> payloadType = Class.forName(
                "org.example.ticket.reservation.queue.application.ReservationQueuePayload"
        );

        assertThat(payloadType.getDeclaredMethod("schemaVersion")).isNotNull();
        assertThat(payloadType.getDeclaredMethod("memberId")).isNotNull();
        assertThat(payloadType.getDeclaredMethod("idempotencyKey")).isNotNull();
        assertThat(payloadType.getDeclaredMethod("requestHash")).isNotNull();
        assertThat(payloadType.getDeclaredMethod("normalizedSeatIds")).isNotNull();
    }

    @Test
    void queueControllerUsesVerifiedPrincipalMemberId() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/example/ticket/reservation/queue/api/ReservationQueueController.java"
        ));

        assertThat(source).contains("MetamaskUserDetails", "getMemberId()");
    }

    @Test
    void enqueueLuaStoresWorkerAndRecoveryFields() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/resources/redis/reservation-queue/reservation_queue_enqueue.lua"
        ));

        assertThat(source).contains(
                "payloadSchemaVersion",
                "memberId",
                "idempotencyKey",
                "idempotencyKeyHash",
                "ownerToken"
        );
    }
}
