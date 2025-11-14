package org.example.ticket.performance.service;/*
package org.example.ticket.event.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.ticket.event.model.EventDetails;
import org.example.ticket.event.model.EventOverview;
import org.example.ticket.event.model.dto.EventGeneralRequest;
import org.example.ticket.event.model.dto.EventOverviewRequest;
import org.example.ticket.event.repository.EventDetailsRepository;
import org.example.ticket.event.repository.EventOverviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest
@ActiveProfiles("persistent-test")
@Transactional
@Commit
public class EventServicePersistentIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventOverviewRepository eventOverviewRepository;

    // EventDetailsRepository도 주입받아 EventDetails 직접 생성/관리에 사용할 수 있음
    @Autowired
    private EventDetailsRepository eventDetailsRepository;


    @BeforeEach
    void setUp() {
        // 각 테스트 실행 전 DB 데이터 초기화
        // 연관관계로 인해 EventOverview만 삭제해도 EventDetails가 삭제(orphanRemoval=true)될 수 있지만,
        // 명시적으로 둘 다 삭제하거나 순서를 지켜 삭제하는 것이 안전할 수 있다.
        // 또는 EventOverview가 Details를 항상 가지므로 Overview만 삭제해도 충분.
        eventOverviewRepository.deleteAll();
        // eventDetailsRepository.deleteAll(); // 필요시
    }

    // (이전 답변에서 제공된 enterEventDetails 테스트 메서드는 여기에 이미 있다고 가정)
    // @Test
    // @DisplayName("EventService.enterEventDetails 호출 시 이벤트 정보가 실제 DB에 저장(커밋)된다")
    // void whenEnterEventDetails_thenEventIsPersistedAndCommitted() { ... }


    @Nested
    @DisplayName("viewTicketDetails 메서드 테스트")
    class ViewTicketDetailsTests {

        private EventOverview savedEvent1;

        @BeforeEach
        void setupViewDetailsData() {
            // 테스트용 데이터 준비 및 저장
            EventDetails details1 = EventDetails.builder()
                    .place("상세 조회용 공연장")
                    .time(150)
                    .age(18)
                    .price(85000)
                    .content("이것은 상세 조회 테스트를 위한 내용입니다.")
                    .build();
            // EventDetails는 EventOverview를 통해 CascadeType.ALL로 저장되므로 먼저 persist할 필요 없음

            EventOverview overview1 = EventOverview.builder()
                    .title("상세 조회 테스트 이벤트")
                    .startDate(LocalDate.of(2026, 8, 1))
                    .endDate(LocalDate.of(2026, 8, 10))
                    .image("http://example.com/view_details_event.jpg")
                    .details(details1) // EventDetails 객체 연결
                    .build();
            savedEvent1 = eventOverviewRepository.saveAndFlush(overview1); // ID를 즉시 할당받기 위해 saveAndFlush 사용
        }

        @Test
        @DisplayName("존재하는 이벤트 ID로 조회 시 해당 이벤트의 상세 정보 DTO를 반환한다")
        void whenEventExists_thenReturnEventDetailsDto() {
            // 실행
            EventGeneralRequest resultDto = eventService.viewTicketDetails(savedEvent1.getId());

            // 검증
            assertThat(resultDto).isNotNull();
            assertThat(resultDto.getTitle()).isEqualTo(savedEvent1.getTitle());
            assertThat(resultDto.getImageUrl()).isEqualTo(savedEvent1.getImage());
            assertThat(resultDto.getStartDate()).isEqualTo(savedEvent1.getStartDate());
            assertThat(resultDto.getEndDate()).isEqualTo(savedEvent1.getEndDate());

            assertThat(resultDto.getPlace()).isEqualTo(savedEvent1.getDetails().getPlace());
            assertThat(resultDto.getTime()).isEqualTo(savedEvent1.getDetails().getTime());
            assertThat(resultDto.getAge()).isEqualTo(savedEvent1.getDetails().getAge());
            assertThat(resultDto.getPrice()).isEqualTo(savedEvent1.getDetails().getPrice());
            assertThat(resultDto.getContent()).isEqualTo(savedEvent1.getDetails().getContent());
        }

        @Test
        @DisplayName("존재하지 않는 이벤트 ID로 조회 시 EntityNotFoundException을 던진다")
        void whenEventDoesNotExist_thenThrowEntityNotFoundException() {
            Long nonExistentId = 9999L; // 존재하지 않는 ID

            // 실행 및 검증
            assertThatThrownBy(() -> eventService.viewTicketDetails(nonExistentId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Event not found with ID: " + nonExistentId);
        }
    }


    @Nested
    @DisplayName("viewTicketList 메서드 테스트")
    class ViewTicketListTests {

        @BeforeEach
        void setupViewListData() {
            // 테스트용 데이터 여러 개 준비 및 저장
            List<EventOverview> eventsToSave = new ArrayList<>();

            EventDetails details1 = EventDetails.builder().place("목록1 장소").time(90).age(10).price(10000).content("목록1 내용").build();
            eventsToSave.add(EventOverview.builder().title("목록 테스트 이벤트 1").startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 5)).image("img1.jpg").details(details1).build());

            EventDetails details2 = EventDetails.builder().place("목록2 장소").time(120).age(15).price(20000).content("목록2 내용").build();
            eventsToSave.add(EventOverview.builder().title("목록 테스트 이벤트 2").startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2026, 10, 5)).image("img2.jpg").details(details2).build());

            eventOverviewRepository.saveAllAndFlush(eventsToSave);
        }

        @Test
        @DisplayName("저장된 이벤트가 여러 건 있을 때 모든 이벤트 요약 정보 DTO 리스트를 반환한다")
        void whenEventsExist_thenReturnListOfEventOverviewDtos() {
            // 실행
            List<EventOverviewRequest> resultList = eventService.viewTicketList();

            // 검증
            assertThat(resultList).isNotNull();
            assertThat(resultList).hasSize(2); // 위에서 2개 저장했으므로

            // 첫 번째 이벤트 검증 (순서는 ID 또는 특정 필드 정렬에 따라 달라질 수 있으므로, 내용으로 확인 권장)
            EventOverviewRequest dto1 = resultList.stream().filter(dto -> "목록 테스트 이벤트 1".equals(dto.getTitle())).findFirst().orElse(null);
            assertThat(dto1).isNotNull();
            assertThat(dto1.getImageUrl()).isEqualTo("img1.jpg");
            assertThat(dto1.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(dto1.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 5));


            EventOverviewRequest dto2 = resultList.stream().filter(dto -> "목록 테스트 이벤트 2".equals(dto.getTitle())).findFirst().orElse(null);
            assertThat(dto2).isNotNull();
            assertThat(dto2.getImageUrl()).isEqualTo("img2.jpg");
            assertThat(dto2.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(dto2.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        @Test
        @DisplayName("저장된 이벤트가 없을 때 빈 DTO 리스트를 반환한다")
        void whenNoEventsExist_thenReturnEmptyList() {
            // Setup에서 deleteAll을 했고, 이 테스트 케이스를 위한 별도 데이터 추가 안함
            // 또는 명시적으로 eventOverviewRepository.deleteAll(); 한번 더 호출
            eventOverviewRepository.deleteAll(); // 확실하게 비우기

            // 실행
            List<EventOverviewRequest> resultList = eventService.viewTicketList();

            // 검증
            assertThat(resultList).isNotNull();
            assertThat(resultList).isEmpty();
        }
    }
}*/
