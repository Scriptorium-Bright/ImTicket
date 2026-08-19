package org.example.ticket.reservation.waitingroom.controller;

import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffResponse;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffSubmission;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffService;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.example.ticket.member.model.Member;
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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaitingRoomAsyncJoinControllerWebContractTest {

    private static final UUID REQUEST_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private WaitingRoomJoinHandoffService handoffService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WaitingRoomService waitingRoomService = mock(WaitingRoomService.class);
        handoffService = mock(WaitingRoomJoinHandoffService.class);
        when(handoffService.enabled()).thenReturn(true);
        when(handoffService.findExistingTicket(7L, 12L)).thenReturn(Optional.empty());
        when(handoffService.submit(7L, 12L)).thenReturn(new WaitingRoomJoinHandoffSubmission(REQUEST_ID, true));
        when(handoffService.queuedResponse(eq(new WaitingRoomJoinHandoffSubmission(REQUEST_ID, true))))
                .thenReturn(new WaitingRoomJoinHandoffResponse(REQUEST_ID, WaitingRoomJoinHandoffStatus.QUEUED, 1_000L));
        mockMvc = MockMvcBuilders.standaloneSetup(new WaitingRoomController(waitingRoomService, handoffService))
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build();
    }

    @Test
    void acceptsJoinAndReturnsReconnectableRequestLocation() throws Exception {
        mockMvc.perform(post("/api/reservation/waiting-room/7/join")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/reservation/waiting-room/7/join-requests/" + REQUEST_ID))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

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
