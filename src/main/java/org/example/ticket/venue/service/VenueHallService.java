package org.example.ticket.venue.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.example.ticket.venue.dto.request.*;
import org.example.ticket.venue.dto.response.VenueHallResponse;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.model.VenueHallSeatTemplate;
import org.example.ticket.venue.repository.VenueHallRepository;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class VenueHallService {

    private final VenueHallRepository venueHallRepository;
    private final VenueHallSeatTemplateRepository seatTemplateRepository;

    public List<VenueHallResponse> viewVenueHallList() {
        return venueHallRepository.findAllAsVenueHallResponse();
    }

    @Async("seatCreationTaskExecutor")
    @Transactional
    public void allocateEmptySeatTemplate(Long hallId, List<VenueHallFloorRequest> floorRequestList) {
        VenueHall hall = venueHallRepository.findById(hallId)
                .orElseThrow(() -> new EntityNotFoundException("공연장을 찾을 수 없습니다."));

        List<VenueHallSeatTemplate> templates = buildSeatTemplates(hall, floorRequestList);
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("저장할 좌석 템플릿이 없습니다.");
        }

        seatTemplateRepository.deleteByVenueHallId(hallId);
        seatTemplateRepository.saveAll(templates);
    }

    private List<VenueHallSeatTemplate> buildSeatTemplates(VenueHall hall, List<VenueHallFloorRequest> floorRequestList) {
        List<VenueHallSeatTemplate> templates = new ArrayList<>();

        floorRequestList.forEach(floorDTO ->
                floorDTO.getSection().forEach(sectionDTO ->
                        sectionDTO.getRows().forEach(rowDTO ->
                                rowDTO.getSeats().forEach(seatDTO ->
                                        templates.addAll(buildSeatTemplates(hall, floorDTO, sectionDTO, rowDTO, seatDTO))
                                )
                        )
                )
        );

        return templates;
    }

    private List<VenueHallSeatTemplate> buildSeatTemplates(
            VenueHall hall,
            VenueHallFloorRequest floorDTO,
            VenueHallSectionRequest sectionDTO,
            VenueHallRowRequest rowDTO,
            VenueHallSeatRequest seatDTO
    ) {
        return IntStream.rangeClosed(seatDTO.getStartSeatNumber(), seatDTO.getEndSeatNumber())
                .mapToObj(seatNumber -> VenueHallSeatTemplate.builder()
                        .venueHall(hall)
                        .floor(floorDTO.getFloor())
                        .section(sectionDTO.getSection())
                        .row(rowDTO.getRow())
                        .seatNumber(seatNumber)
                        .seatInfo(seatDTO.getSeatInfo())
                        .build())
                .toList();
    }

}
