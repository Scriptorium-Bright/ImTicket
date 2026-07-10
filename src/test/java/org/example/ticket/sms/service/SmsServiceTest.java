package org.example.ticket.sms.service;

import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.sms.request.SmsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import javax.naming.AuthenticationException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private DefaultMessageService messageService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private SMSService smsService;

    @Test
    void sendMessageStoresSixDigitCodeAndCallsProvider() throws AuthenticationException {
        SmsRequest request = new SmsRequest("01012345678", null);
        ReflectionTestUtils.setField(smsService, "from", "01000000000");
        when(memberRepository.existsMemberByPhoneNumber(request.getTo())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        boolean sent = smsService.sendMessage(request);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("sms:" + request.getTo()),
                codeCaptor.capture(),
                eq(Duration.ofMinutes(1))
        );
        assertThat(codeCaptor.getValue()).matches("\\d{6}");

        ArgumentCaptor<SingleMessageSendingRequest> messageCaptor =
                ArgumentCaptor.forClass(SingleMessageSendingRequest.class);
        verify(messageService).sendOne(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessage().getFrom()).isEqualTo("01000000000");
        assertThat(messageCaptor.getValue().getMessage().getTo()).isEqualTo(request.getTo());
        assertThat(messageCaptor.getValue().getMessage().getText()).contains(codeCaptor.getValue());
        assertThat(sent).isTrue();
    }

    @Test
    void sendMessageRejectsAlreadyRegisteredPhoneNumber() {
        SmsRequest request = new SmsRequest("01012345678", null);
        when(memberRepository.existsMemberByPhoneNumber(request.getTo())).thenReturn(true);

        assertThrows(AuthenticationException.class, () -> smsService.sendMessage(request));

        verifyNoInteractions(redisTemplate, messageService);
    }

    @Test
    void verifiedCodeDeletesStoredCodeWhenMatched() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("sms:01012345678")).thenReturn("123456");

        boolean verified = smsService.verifiedCode("01012345678", "123456");

        assertThat(verified).isTrue();
        verify(redisTemplate).delete("sms:01012345678");
    }

    @Test
    void verifiedCodeAllowsTestCodeOnlyWhenEnabled() {
        ReflectionTestUtils.setField(smsService, "allowTestCode", true);

        assertThat(smsService.verifiedCode("01012345678", "000000")).isTrue();

        verifyNoInteractions(redisTemplate);
    }
}
