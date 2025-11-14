package org.example.ticket.sms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.sms.request.SmsRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import java.time.Duration;
import java.util.Random;

@Service
@Slf4j
@RequiredArgsConstructor
public class SMSService {

    private final MemberRepository memberRepository;

    private static final String SMS_KEY ="sms:";

    @Value("${coolsms.api.from}")
    private String from;

    private final DefaultMessageService messageService;
    private final RedisTemplate<String, String> redisTemplate;

    private final Random random = new Random();

    public boolean sendMessage(SmsRequest request) throws AuthenticationException {

        if(memberRepository.existsMemberByPhoneNumber(request.getTo())) {
            throw new AuthenticationException("이미 존재하는 휴대폰 번호입니다.");
        }

        String code = generateRandomCertificationCode(request);
        log.info(code);
        /*


        Message message = new Message();
        message.setFrom(from);
        message.setTo(request.getTo());
        message.setText("[I'm 표] 인증번호는 [" + code + "] 입니다.");

        log.info("send success");
        messageService.sendOne(new SingleMessageSendingRequest(message));*/

        return code != null;
    }

    public boolean verifiedCode(String phoneNumber, String code) {
        String usersKey = SMS_KEY + phoneNumber;
        String storedCode = redisTemplate.opsForValue().get(usersKey);
        log.info(storedCode);
        log.info(usersKey);
        if (code != null && code.equals(storedCode)) {
            redisTemplate.delete(usersKey);
            return true;
        }

        return false;

    }

    public String generateRandomCertificationCode(SmsRequest request) {
        // 무작위 6자리 코드 생성
        String randomNumber = String.valueOf(random.nextInt(900000) + 100000);
        redisTemplate.opsForValue().set(SMS_KEY + request.getTo(), randomNumber, Duration.ofMinutes(1));
        return randomNumber;
    }
}