package org.example.ticket.reservation.booking.api;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.common.exception.GlobalExceptionHandler;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.api.ReservationCreateResponse;
import org.example.ticket.reservation.booking.application.ReservationPreReserveService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationControllerWebContractTest {

    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";

    private ReservationPreReserveService preReserveService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        preReserveService = mock(ReservationPreReserveService.class);
        ReservationController controller = new ReservationController(preReserveService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build();
    }

    @Test
    void missingIdempotencyHeaderUsesDomain400Response() throws Exception {
        when(preReserveService.preReserve(eq("0xowner"), isNull(), any(ReservationRequest.class)))
                .thenThrow(new BusinessException(ReservationErrorCode.IDEMPOTENCY_KEY_REQUIRED));

        mockMvc.perform(post("/api/reservation/pre-reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"performanceTimeId":1,"seatIds":[11]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void validHeaderReturnsSnapshotCapableResponseContract() throws Exception {
        when(preReserveService.preReserve(eq("0xowner"), eq(KEY), any(ReservationRequest.class)))
                .thenReturn(ReservationCreateResponse.builder()
                        .id(10L)
                        .orderUid("reservation-10")
                        .responses(List.of())
                        .build());

        mockMvc.perform(post("/api/reservation/pre-reserve")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"performanceTimeId":1,"seatIds":[11]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.orderUid").value("reservation-10"));
    }

    private HandlerMethodArgumentResolver authenticationPrincipalResolver() {
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
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
