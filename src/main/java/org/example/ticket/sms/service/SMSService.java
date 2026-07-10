package org.example.ticket.sms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.sms.request.SmsRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import java.security.SecureRandom;
import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class SMSService {

    private final MemberRepository memberRepository;

    private static final String SMS_KEY ="sms:";
    private static final int CERTIFICATION_CODE_BOUND = 1_000_000;

    @Value("${coolsms.api.from}")
    private String from;

    @Value("${ticket.sms.allow-test-code:false}")
    private boolean allowTestCode;

    private final DefaultMessageService messageService;
    private final RedisTemplate<String, String> redisTemplate;

    private final SecureRandom secureRandom = new SecureRandom();

    public boolean sendMessage(SmsRequest request) throws AuthenticationException {

        if(memberRepository.existsMemberByPhoneNumber(request.getTo())) {
            throw new AuthenticationException("이미 존재하는 휴대폰 번호입니다.");
        }

        String code = generateRandomCertificationCode(request);

        Message message = new Message();
        message.setFrom(from);
        message.setTo(request.getTo());
        message.setText("[I'm 표] 인증번호는 [" + code + "] 입니다.");

        messageService.sendOne(new SingleMessageSendingRequest(message));
        log.info("SMS verification code sent. to={}", maskPhoneNumber(request.getTo()));

        return true;
    }

    public boolean verifiedCode(String phoneNumber, String code) {
        if (allowTestCode && "000000".equals(code)) {
            return true;
        }

        String usersKey = SMS_KEY + phoneNumber;
        String storedCode = redisTemplate.opsForValue().get(usersKey);
        if (code != null && code.equals(storedCode)) {
            redisTemplate.delete(usersKey);
            return true;
        }

        return false;

    }

    public String generateRandomCertificationCode(SmsRequest request) {
        String randomNumber = String.format("%06d", secureRandom.nextInt(CERTIFICATION_CODE_BOUND));
        redisTemplate.opsForValue().set(SMS_KEY + request.getTo(), randomNumber, Duration.ofMinutes(1));
        return randomNumber;
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        return "****" + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
