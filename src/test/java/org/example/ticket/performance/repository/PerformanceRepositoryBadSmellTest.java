package org.example.ticket.performance.repository;

import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.model.SeatPrice;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
public class PerformanceRepositoryBadSmellTest {

    @Autowired
    private PerformanceRepository performanceRepository;

    @Test
    @Disabled("리팩토링 후보를 드러내는 재현용 테스트라 기본 테스트 게이트에서는 제외한다.")
    @DisplayName("배드스멜 테스트: MultipleBagFetchException 또는 Cartesian Product 폭발 확인")
    public void testMultipleBagFetchException() {
        // given: 2개의 리스트(seatPrices, performanceTimes)를 가진 Performance 생성
        Performance performance = Performance.builder()
                .title("테스트 공연")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(1))
                .build();
        
        // 2개의 가격 정보 추가
        performance.addPrice(SeatPrice.builder().price(10000).build());
        performance.addPrice(SeatPrice.builder().price(20000).build());
        
        // 2개의 공연 시간 추가
        PerformanceTime time1 = PerformanceTime.builder().performance(performance).build();
        PerformanceTime time2 = PerformanceTime.builder().performance(performance).build();
        performance.getPerformanceTimes().add(time1);
        performance.getPerformanceTimes().add(time2);

        performanceRepository.saveAndFlush(performance);

        // when & then: 2개 이상의 Bag(List)을 동시에 JOIN FETCH 할 때 예외가 발생하는지 확인
        // 하이버네이트 설정에 따라 MultipleBagFetchException이 발생하거나,
        // 예외가 안 나더라도 M x N (2x2=4) 개의 데이터가 메모리로 로딩되는 비효율 발생
        assertThrows(Exception.class, () -> {
            performanceRepository.findByIdWithDetails(performance.getId());
        }, "2개 이상의 List를 동시에 JOIN FETCH하면 MultipleBagFetchException이 발생해야 합니다.");
    }
}
