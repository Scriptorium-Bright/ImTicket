package org.example.ticket.performance.service;/*
package org.example.ticket.event.service; // EventService와 같은 패키지 또는 하위 테스트 패키지

import org.example.ticket.event.model.EventOverview;
import org.example.ticket.event.model.dto.EventGeneralRequest;
import org.example.ticket.event.model.dto.EventOverviewRequest;
import org.example.ticket.event.repository.EventOverviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit; // @Commit 어노테이션
import org.springframework.test.context.ActiveProfiles; // 프로파일 활성화
import org.springframework.transaction.annotation.Transactional; // 트랜잭션 관리

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest // Spring Boot 전체 컨텍스트 로드
@ActiveProfiles("persistent-test") // "persistent-test" 프로파일 활성화
@Transactional // 테스트 메서드를 트랜잭션 내에서 실행
@Commit       // 테스트 성공 시 트랜잭션 커밋
public class EventSaveTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventOverviewRepository eventOverviewRepository;

    @BeforeEach
    void setUp() {
        // 각 테스트 실행 전에 DB를 초기화하여 테스트 독립성 확보
        eventOverviewRepository.deleteAll();
    }

    @Test
    @DisplayName("EventService.enterEventDetails 호출 시 이벤트 정보가 실제 DB에 저장(커밋)된다")
    void whenEnterEventDetails_thenEventIsPersistedAndCommitted() {
        // 1. 준비 (Arrange)
        EventGeneralRequest detailsRequest = EventGeneralRequest.builder()
                .place("서비스 테스트 공연장")
                .time(120)
                .age(12)
                .price(77000)
                .content("서비스 계층에서 직접 생성된 이벤트 상세 내용입니다. DB에 커밋됩니다.")
                // EventDetails.from()이 사용하지 않는 필드는 생략하거나 DTO 정의에 맞게 포함 가능
                .title("불필요한 타이틀")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(1))
                .imageUrl("no_image.jpg")
                .build();

        EventOverviewRequest overviewRequest = EventOverviewRequest.builder()
                .title("서비스 통합 테스트 이벤트 (DB 커밋)")
                .startDate(LocalDate.of(2026, 7, 1))
                .endDate(LocalDate.of(2026, 7, 10)) // EventOverview.from()이 이 값을 사용한다고 가정
                .imageUrl("http://example.com/service_it_commit.jpg")
                .build();

        long countBeforeSave = eventOverviewRepository.count();

        // 2. 실행 (Act)
        eventService.enterEventDetails(detailsRequest, overviewRequest);
        // @Commit 어노테이션으로 인해 이 시점에서 DB에 커밋됨 (테스트 메서드 성공 종료 시)

        // 3. 검증 (Assert)
        // DB에서 직접 데이터를 조회하여 확인
        List<EventOverview> allEventsInDb = eventOverviewRepository.findAll();
        assertThat(allEventsInDb).hasSize((int) (countBeforeSave + 1));

        // 저장된 이벤트를 찾아 상세 검증 (여기서는 제목으로 구분, 실제로는 ID 반환받아 조회 권장)
        Optional<EventOverview> foundEventOptional = allEventsInDb.stream()
                .filter(e -> overviewRequest.getTitle().equals(e.getTitle()))
                .findFirst();

        assertThat(foundEventOptional).isPresent();
        EventOverview savedOverview = foundEventOptional.get();

        assertThat(savedOverview.getId()).isNotNull(); // ID가 생성되었는지 확인
        assertThat(savedOverview.getImage()).isEqualTo(overviewRequest.getImageUrl());
        assertThat(savedOverview.getStartDate()).isEqualTo(overviewRequest.getStartDate());
        // EventOverview.from()에서 endDate를 overviewRequest.getEndDate()로 올바르게 설정했다고 가정
        assertThat(savedOverview.getEndDate()).isEqualTo(overviewRequest.getEndDate());

        assertThat(savedOverview.getDetails()).isNotNull();
        assertThat(savedOverview.getDetails().getId()).isNotNull(); // EventDetails의 ID도 생성되었는지 확인
        assertThat(savedOverview.getDetails().getPlace()).isEqualTo(detailsRequest.getPlace());
        assertThat(savedOverview.getDetails().getTime()).isEqualTo(detailsRequest.getTime());
        assertThat(savedOverview.getDetails().getAge()).isEqualTo(detailsRequest.getAge());
        assertThat(savedOverview.getDetails().getPrice()).isEqualTo(detailsRequest.getPrice());
        assertThat(savedOverview.getDetails().getContent()).isEqualTo(detailsRequest.getContent());

        System.out.println("EventServicePersistentIntegrationTest: 이벤트 ID " + savedOverview.getId() +
                " ('" + savedOverview.getTitle() + "')가 DB에 성공적으로 저장(커밋)되었습니다.");
        System.out.println("'persistent-test' 프로파일에 설정된 DB(" +
                // 실제 DB URL을 출력하거나 파일 경로 안내
                "예: ./target/persistent_service_test_db.mv.db" +
                ")에서 확인 가능합니다.");
    }
}*/
