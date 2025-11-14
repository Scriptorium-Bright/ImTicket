package org.example.ticket.performance.service;/*
package org.example.ticket.event.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.ticket.event.model.EventDetails;
import org.example.ticket.event.model.EventOverview;
import org.example.ticket.event.model.dto.EventGeneralRequest;
import org.example.ticket.event.model.dto.EventOverviewRequest;
import org.example.ticket.event.repository.EventOverviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)

class EventServiceTest {

    @Mock
    private EventOverviewRepository overviewRepositoryMock;

    @InjectMocks
    private EventService eventService;

    private EventGeneralRequest.EventDetailsRequestBuilder detailsRequestBuilder;
    private EventOverviewRequest.EventOverviewRequestBuilder overviewRequestBuilder;

    @BeforeEach
    void setUp() {
        detailsRequestBuilder = EventGeneralRequest.builder()
                .place("Test Place")
                .time(120)
                .age(15)
                .price(50000)
                .content("Test Content")
                .title("Shared Test Title")
                .startDate(LocalDate.of(2025, 10, 1))
                .endDate(LocalDate.of(2025, 10, 10))
                .imageUrl("http://example.com/shared_image.jpg");

        overviewRequestBuilder = EventOverviewRequest.builder()
                .title("Test Overview Title")
                .startDate(LocalDate.of(2025, 11, 1))
                .endDate(LocalDate.of(2025, 11, 10))
                .imageUrl("http://example.com/overview_image.jpg");
    }

    @Nested
    @DisplayName("enterEventDetails 메서드 테스트")
    class Describe_enterEventDetails {

        @Test
        @DisplayName("유효한 요청이 주어지면 EventDetails와 EventOverview를 생성하고 EventOverview를 저장한다")
        void whenValidRequests_thenCreatesAndSavesEventOverview() {
            EventGeneralRequest detailsRequest = detailsRequestBuilder.build();
            EventOverviewRequest overviewRequest = overviewRequestBuilder.build();

            eventService.enterEventDetails(detailsRequest, overviewRequest);

            ArgumentCaptor<EventOverview> overviewArgumentCaptor = ArgumentCaptor.forClass(EventOverview.class);
            verify(overviewRepositoryMock).save(overviewArgumentCaptor.capture());

            EventOverview savedOverview = overviewArgumentCaptor.getValue();
            assertThat(savedOverview).isNotNull();
            assertThat(savedOverview.getTitle()).isEqualTo(overviewRequest.getTitle());
            assertThat(savedOverview.getImage()).isEqualTo(overviewRequest.getImageUrl());
            assertThat(savedOverview.getStartDate()).isEqualTo(overviewRequest.getStartDate());
            // EventOverview.from의 현재 로직에 따라 endDate가 startDate로 설정되는 것을 반영
            assertThat(savedOverview.getEndDate()).isEqualTo(overviewRequest.getStartDate());


            EventDetails savedDetails = savedOverview.getDetails();
            assertThat(savedDetails).isNotNull();
            assertThat(savedDetails.getPlace()).isEqualTo(detailsRequest.getPlace());
            assertThat(savedDetails.getTime()).isEqualTo(detailsRequest.getTime());
            assertThat(savedDetails.getAge()).isEqualTo(detailsRequest.getAge());
            assertThat(savedDetails.getPrice()).isEqualTo(detailsRequest.getPrice());
            assertThat(savedDetails.getContent()).isEqualTo(detailsRequest.getContent());
        }
    }

    @Nested
    @DisplayName("viewTicketDetails 메서드 테스트")
    class Describe_viewTicketDetails {

        private Long existingEventId;
        private Long nonExistingEventId;
        private EventOverview mockEventOverview;
        private EventDetails mockEventDetails;

        @BeforeEach
        void setUp() {
            existingEventId = 1L;
            nonExistingEventId = 2L;

            mockEventDetails = EventDetails.builder()
                    .id(100L)
                    .place("Concert Hall")
                    .time(150)
                    .age(18)
                    .price(75000)
                    .content("An amazing concert experience.")
                    .build();

            mockEventOverview = EventOverview.builder()
                    .id(existingEventId)
                    .title("Grand Concert")
                    .image("http://example.com/grand_concert.jpg")
                    .startDate(LocalDate.of(2025, 12, 1))
                    .endDate(LocalDate.of(2025, 12, 5))
                    .details(mockEventDetails)
                    .build();
        }

        @Test
        @DisplayName("존재하는 이벤트 ID로 조회하면 해당 이벤트 상세 정보를 DTO로 반환한다")
        void whenEventExists_thenReturnEventDetailsRequestDto() {
            when(overviewRepositoryMock.findByDetails(existingEventId)).thenReturn(Optional.of(mockEventOverview));

            EventGeneralRequest resultDto = eventService.viewTicketDetails(existingEventId);

            assertThat(resultDto).isNotNull();
            assertThat(resultDto.getTitle()).isEqualTo(mockEventOverview.getTitle());
            assertThat(resultDto.getImageUrl()).isEqualTo(mockEventOverview.getImage());
            assertThat(resultDto.getStartDate()).isEqualTo(mockEventOverview.getStartDate());
            assertThat(resultDto.getEndDate()).isEqualTo(mockEventOverview.getEndDate());

            assertThat(resultDto.getPlace()).isEqualTo(mockEventDetails.getPlace());
            assertThat(resultDto.getTime()).isEqualTo(mockEventDetails.getTime());
            assertThat(resultDto.getAge()).isEqualTo(mockEventDetails.getAge());
            assertThat(resultDto.getPrice()).isEqualTo(mockEventDetails.getPrice());
            assertThat(resultDto.getContent()).isEqualTo(mockEventDetails.getContent());

            verify(overviewRepositoryMock).findByDetails(existingEventId);
        }

        @Test
        @DisplayName("존재하지 않는 이벤트 ID로 조회하면 EntityNotFoundException을 던진다")
        void whenEventDoesNotExist_thenThrowEntityNotFoundException() {
            when(overviewRepositoryMock.findByDetails(nonExistingEventId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> eventService.viewTicketDetails(nonExistingEventId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Event not found with ID: " + nonExistingEventId);

            verify(overviewRepositoryMock).findByDetails(nonExistingEventId);
        }

        @Test
        @DisplayName("이벤트는 존재하지만 세부 정보(EventDetails)가 null인 경우, DTO 매핑 시 NullPointerException이 발생할 수 있다")
        void whenEventExistsButDetailsAreNull_thenMappingMightCauseNPE() {
            EventOverview overviewWithNullDetails = EventOverview.builder()
                    .id(existingEventId)
                    .title("Event With Null Details")
                    .image("http://example.com/null_details.jpg")
                    .startDate(LocalDate.of(2026, 1, 1))
                    .endDate(LocalDate.of(2026, 1, 5))
                    .details(null) // EventDetails가 null인 상황
                    .build();

            when(overviewRepositoryMock.findByDetails(existingEventId)).thenReturn(Optional.of(overviewWithNullDetails));

            // EventGeneralRequest.of() 메서드가 null인 details의 필드에 접근하려고 할 때 NPE 발생
            assertThatThrownBy(() -> eventService.viewTicketDetails(existingEventId))
                    .isInstanceOf(NullPointerException.class);

            verify(overviewRepositoryMock).findByDetails(existingEventId);
        }
    }

    @Nested
    @DisplayName("viewTicketList 메서드 테스트")
    class Describe_viewTicketList {

        @Test
        @DisplayName("저장된 이벤트가 여러 건 있을 때 모든 이벤트 요약 정보를 DTO 리스트로 반환한다")
        void whenMultipleEventsExist_thenReturnListOfEventOverviewRequestDtos() {
            EventDetails details1 = EventDetails.builder().id(101L).place("Place 1").build();
            EventOverview overview1 = EventOverview.builder()
                    .id(1L)
                    .title("Event 1")
                    .image("img1.jpg")
                    .startDate(LocalDate.of(2025, 1, 1))
                    .endDate(LocalDate.of(2025, 1, 2))
                    .details(details1)
                    .build();

            EventDetails details2 = EventDetails.builder().id(102L).place("Place 2").build();
            EventOverview overview2 = EventOverview.builder()
                    .id(2L)
                    .title("Event 2")
                    .image("img2.jpg")
                    .startDate(LocalDate.of(2025, 2, 1))
                    .endDate(LocalDate.of(2025, 2, 2))
                    .details(details2)
                    .build();

            List<EventOverview> mockOverviewList = List.of(overview1, overview2);
            when(overviewRepositoryMock.findAll()).thenReturn(mockOverviewList);

            List<EventOverviewRequest> resultList = eventService.viewTicketList();

            assertThat(resultList).isNotNull();
            assertThat(resultList).hasSize(2);

            assertThat(resultList.get(0).getTitle()).isEqualTo(overview1.getTitle());
            assertThat(resultList.get(0).getImageUrl()).isEqualTo(overview1.getImage());
            assertThat(resultList.get(0).getStartDate()).isEqualTo(overview1.getStartDate());
            assertThat(resultList.get(0).getEndDate()).isEqualTo(overview1.getEndDate());

            assertThat(resultList.get(1).getTitle()).isEqualTo(overview2.getTitle());
            assertThat(resultList.get(1).getImageUrl()).isEqualTo(overview2.getImage());
            assertThat(resultList.get(1).getStartDate()).isEqualTo(overview2.getStartDate());
            assertThat(resultList.get(1).getEndDate()).isEqualTo(overview2.getEndDate());

            verify(overviewRepositoryMock).findAll();
        }

        @Test
        @DisplayName("저장된 이벤트가 없을 때 빈 DTO 리스트를 반환한다")
        void whenNoEventsExist_thenReturnEmptyListOfEventOverviewRequestDtos() {
            when(overviewRepositoryMock.findAll()).thenReturn(Collections.emptyList());

            List<EventOverviewRequest> resultList = eventService.viewTicketList();

            assertThat(resultList).isNotNull();
            assertThat(resultList).isEmpty();

            verify(overviewRepositoryMock).findAll();
        }
    }
}*/
