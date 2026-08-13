package org.example.ticket.reservation.waitingroom.controller;

import org.example.ticket.common.response.ApiResponse;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaitingRoomControllerWebContractTest {

    private static final UUID TICKET_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private WaitingRoomService service;
    private MockMvc mockMvc;

    /** 인증 principal resolver와 mock service를 사용해 controller contract를 구성한다. */
    @BeforeEach
    void setUp() {
        service = mock(WaitingRoomService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WaitingRoomController(service))
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build();
    }

    /** 계획된 join path가 인증 회원 ID를 service로 전달하는지 검증한다. */
    @Test
    void joinsThroughPlannedEndpoint() throws Exception {
        when(service.join(7L, 12L)).thenReturn(waitingResponse());

        mockMvc.perform(post("/api/reservation/waiting-room/7/join")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(TICKET_ID.toString()))
                .andExpect(jsonPath("$.data.position").value(1));
    }

    /** 계획된 status와 cancel path가 같은 ticket lifecycle을 호출하는지 검증한다. */
    @Test
    void readsStatusAndCancelsThroughPlannedEndpoints() throws Exception {
        when(service.status(7L, 12L, TICKET_ID)).thenReturn(waitingResponse());
        when(service.cancel(7L, 12L, TICKET_ID)).thenReturn(cancelledResponse());

        mockMvc.perform(get("/api/reservation/waiting-room/7/tickets/" + TICKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"));
        mockMvc.perform(post("/api/reservation/waiting-room/7/tickets/" + TICKET_ID + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    /** 테스트에서 사용할 WAITING 응답 fixture를 생성한다. */
    private WaitingRoomStatusResponse waitingResponse() {
        return new WaitingRoomStatusResponse(
                TICKET_ID,
                WaitingRoomTicketStatus.WAITING,
                1L,
                1L,
                Instant.parse("2026-08-14T00:30:00Z"),
                null,
                null,
                2000L
        );
    }

    /** 테스트에서 사용할 CANCELED 응답 fixture를 생성한다. */
    private WaitingRoomStatusResponse cancelledResponse() {
        return new WaitingRoomStatusResponse(
                TICKET_ID,
                WaitingRoomTicketStatus.CANCELED,
                null,
                1L,
                Instant.parse("2026-08-14T00:30:00Z"),
                null,
                null,
                2000L
        );
    }

    /** controller가 사용할 인증 회원 principal resolver를 생성한다. */
    private HandlerMethodArgumentResolver authenticationPrincipalResolver() {
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
                .id(12L)
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());
        return new HandlerMethodArgumentResolver() {
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
                return principal;
            }
        };
    }
}
