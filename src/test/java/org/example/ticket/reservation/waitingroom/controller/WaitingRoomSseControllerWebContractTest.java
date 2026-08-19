package org.example.ticket.reservation.waitingroom.controller;

import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomSseNotificationService;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaitingRoomSseControllerWebContractTest {

    private static final UUID TICKET_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private WaitingRoomSseNotificationService notificationService;
    private MockMvc mockMvc;

    /** 인증 principal resolver와 notification service mock으로 SSE controller contract를 구성한다. */
    @BeforeEach
    void setUp() {
        notificationService = mock(WaitingRoomSseNotificationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WaitingRoomSseController(notificationService))
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build();
    }

    /** SSE endpoint가 owner ID를 전달하고 no-store stream response를 여는지 검증한다. */
    @Test
    void opensAuthenticatedTicketStream() throws Exception {
        when(notificationService.open(7L, 12L, TICKET_ID)).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/reservation/waiting-room/7/tickets/" + TICKET_ID + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(notificationService).open(eq(7L), eq(12L), eq(TICKET_ID));
    }

    /** controller가 사용할 인증 principal resolver를 생성한다. */
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
