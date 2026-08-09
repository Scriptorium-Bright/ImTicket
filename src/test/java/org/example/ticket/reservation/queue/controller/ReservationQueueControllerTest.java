package org.example.ticket.reservation.queue.controller;

import org.example.ticket.reservation.queue.dto.response.ReservationQueueEnqueueResponse;
import org.example.ticket.reservation.queue.service.ReservationQueueService;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueStatusResponse;
import org.example.ticket.reservation.queue.domain.ReservationQueueStatus;
import org.example.ticket.member.model.Member;
import org.example.ticket.security.principal.MetamaskUserDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.ticket.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationQueueControllerTest {

    private static final UUID TICKET_ID = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";

    private final ReservationQueueService service = mock(ReservationQueueService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReservationQueueController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void postReturns202AndPollingContract() throws Exception {
        when(service.enqueue(eq(42L), eq("0xowner"), eq(KEY), any())).thenReturn(
                new ReservationQueueEnqueueResponse(
                        42L,
                        TICKET_ID,
                        ReservationQueueStatus.WAITING,
                        false,
                        1_000L,
                        "/api/reservation/pre-reserve/queue/42/" + TICKET_ID
                )
        );

        mockMvc.perform(post("/api/reservation/pre-reserve/queue")
                        .principal(user())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"performanceTimeId":42,"seatIds":[3,1]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(TICKET_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000));
    }

    @Test
    void getReturnsOwnedTicketStatus() throws Exception {
        when(service.status("0xowner", 42L, TICKET_ID)).thenReturn(
                new ReservationQueueStatusResponse(
                        42L,
                        TICKET_ID,
                        ReservationQueueStatus.WAITING,
                        5L,
                        Instant.parse("2026-08-10T10:00:00Z"),
                        Instant.parse("2026-08-10T10:10:00Z"),
                        1_000L
                )
        );

        mockMvc.perform(get("/api/reservation/pre-reserve/queue/42/{ticketId}", TICKET_ID)
                        .principal(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(5))
                .andExpect(jsonPath("$.data.deadlineAt").value("2026-08-10T10:10:00Z"));
    }

    @Test
    void missingAuthenticationUses401QueueError() throws Exception {
        mockMvc.perform(post("/api/reservation/pre-reserve/queue")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"performanceTimeId":42,"seatIds":[1]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("QUEUE_AUTHENTICATION_REQUIRED"));
    }

    @Test
    void principalWithoutMemberIdUses401QueueError() throws Exception {
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());

        mockMvc.perform(post("/api/reservation/pre-reserve/queue")
                        .principal(new UsernamePasswordAuthenticationToken(principal, null, List.of()))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"performanceTimeId":42,"seatIds":[1]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("QUEUE_AUTHENTICATION_REQUIRED"));
    }

    @Test
    void invalidBodyUses400ValidationContract() throws Exception {
        mockMvc.perform(post("/api/reservation/pre-reserve/queue")
                        .principal(user())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"performanceTimeId":0,"seatIds":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void controllerIsEnabledOnlyForQueueDarkLaunch() {
        ConditionalOnProperty condition = ReservationQueueController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("reservation.queue");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    private UsernamePasswordAuthenticationToken user() {
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
                .id(42L)
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }
}
