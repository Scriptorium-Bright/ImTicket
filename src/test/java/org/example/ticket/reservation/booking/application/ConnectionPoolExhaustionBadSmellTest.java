package org.example.ticket.reservation.booking.application;

import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.persistence.ReservationRepository;
import org.example.ticket.reservation.booking.persistence.SeatRepository;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@TestPropertySource(properties = {
        // 커넥션 풀 최대 크기를 3으로 제한
        "spring.datasource.hikari.maximum-pool-size=3",
        // 커넥션을 얻기 위해 기다리는 최대 시간 2초 (기본값 30초에서 단축)
        "spring.datasource.hikari.connection-timeout=2000"
})
public class ConnectionPoolExhaustionBadSmellTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PerformanceRepository performanceRepository;

    @Autowired
    private PerformanceTimeRepository performanceTimeRepository;

    @Autowired
    private SeatRepository seatRepository;

    @SpyBean
    private ReservationRepository reservationRepository;

    @Test
    @Disabled("리팩토링 후보를 드러내는 재현용 테스트라 기본 테스트 게이트에서는 제외한다.")
    @DisplayName("배드스멜 테스트: 비관적 락 경합 시 DB 커넥션 풀이 고갈되어 대기열에 빠지는 현상 검증")
    public void testConnectionPoolExhaustionDueToLockWait() throws InterruptedException {
        // given: 테스트용 데이터 셋업
        Performance performance = Performance.builder()
                .title("커넥션 타임아웃 테스트 공연")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(1))
                .build();
        performanceRepository.save(performance);

        PerformanceTime performanceTime = PerformanceTime.builder()
                .performance(performance)
                .showDate(LocalDate.now())
                .showTime(java.time.LocalTime.NOON)
                .build();
        performanceTimeRepository.save(performanceTime);

        Seat seat = Seat.builder()
                .performanceTime(performanceTime)
                .seatFloor(1).seatSection("A").seatRow(1).seatNumber(1)
                .seatType(org.example.ticket.util.constant.SeatInfo.VIP)
                .price(10000)
                .isReservation(false)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        seatRepository.save(seat);

        int threadCount = 10;
        String testSessionId = UUID.randomUUID().toString().substring(0, 8);
        String[] walletAddresses = new String[threadCount];

        // 10명의 유저 생성
        for (int i = 0; i < threadCount; i++) {
            walletAddresses[i] = "0xUser_" + testSessionId + "_" + i;
            Member member = Member.builder()
                    .walletAddress(walletAddresses[i])
                    .role("USER")
                    .build();
            memberRepository.save(member);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Long targetPerformanceTimeId = performanceTime.getId();
        List<Long> targetSeatIds = List.of(seat.getId());

        // 첫 번째 트랜잭션이 커넥션과 락을 3초간 쥐고 있도록 인위적 지연 발생
        // (HikariCP 타임아웃 2초보다 길게 설정하여 커넥션 풀 고갈을 유도)
        try {
            doAnswer(invocation -> {
                Thread.sleep(3000);
                return invocation.getArgument(0); // callRealMethod()는 JPA 인터페이스에서 불가
            }).when(reservationRepository).save(any());
        } catch (Exception e) {
            e.printStackTrace();
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger lockTimeoutCount = new AtomicInteger(0);
        AtomicInteger connectionTimeoutCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            final String walletAddress = walletAddresses[i];
            executorService.submit(() -> {
                try {
                    ReservationRequest request = new ReservationRequest(targetPerformanceTimeId, targetSeatIds);
                    reservationService.createReservation(walletAddress, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 예외 로그 분석 (HikariCP 타임아웃 vs MySQL 락 타임아웃)
                    String errorMsg = e.toString();
                    if (errorMsg.contains("CannotGetJdbcConnectionException") || errorMsg.contains("ConnectionTimeoutException") || errorMsg.contains("CannotCreateTransactionException")) {
                        connectionTimeoutCount.incrementAndGet();
                    } else if (errorMsg.contains("CannotAcquireLockException") || errorMsg.contains("PessimisticLockException") || errorMsg.contains("LockWaitTimeoutException")) {
                        lockTimeoutCount.incrementAndGet();
                    } else {
                        otherErrorCount.incrementAndGet();
                        System.err.println("기타 에러: " + errorMsg);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // 결과 출력
        System.out.println("=========================================");
        System.out.println("총 요청 수 : " + threadCount);
        System.out.println("예매 성공 : " + successCount.get());
        System.out.println("DB Lock 타임아웃 실패 : " + lockTimeoutCount.get());
        System.out.println("커넥션 풀 고갈 타임아웃 실패 : " + connectionTimeoutCount.get());
        System.out.println("기타 에러 : " + otherErrorCount.get());
        System.out.println("=========================================");

        // then
        // 단 1명만 성공해야 함
        assertTrue(successCount.get() <= 1, "성공 건수는 최대 1건이어야 합니다.");

        // 3개의 커넥션 풀 중 1개는 성공, 2개는 Lock Wait 상태에 빠져 있으므로
        // 남은 7개의 요청은 2초 뒤 커넥션 풀 타임아웃 에러를 발생시켜야 함.
        assertTrue(connectionTimeoutCount.get() > 0,
                "비관적 락 대기로 인해 커넥션 풀이 고갈되어 CannotGetJdbcConnectionException 예외가 발생해야 합니다.");
    }
}
