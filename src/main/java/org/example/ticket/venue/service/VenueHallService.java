package org.example.ticket.venue.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.example.ticket.venue.dto.request.*;
import org.example.ticket.venue.dto.response.VenueHallResponse;
import org.example.ticket.venue.model.*;
import org.example.ticket.venue.repository.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class VenueHallService {

    private final VenueHallRepository venueHallRepository;
    private final VenueHallMapper venueHallMapper;

/*    public void registerVenueHallInformation(Venue venue, List<VenueHallRequest> venueHallRequest) {

        List<VenueHall> venueHallList = venueHallRequest.stream()
                .map(vq -> {
                    return VenueHall.builder()
                            .totalSeats(vq.getTotalSeats())
                            .name(vq.getName())
                            .venue(venue)
                            .build();
                })
                .toList();


        venueHallRepository.saveAll(venueHallList);
    }*/

    public List<VenueHallResponse> viewVenueHallList() {
        return venueHallRepository.findAllAsVenueHallResponse();
    }

    @Async("seatCreationTaskExecutor")
    @Transactional
    public void allocateEmptySeatTemplate(Long hallId, List<VenueHallFloorRequest> floorRequestList) {

        VenueHall hall = venueHallRepository.findById(hallId)
                .orElseThrow(() -> new EntityNotFoundException("공연장을 찾을 수 없습니다."));

        processFloor(floorRequestList, hall);

    }

    private void processFloor(List<VenueHallFloorRequest> floorRequestList, VenueHall hall) {

        floorRequestList.forEach(floorDTO -> {
            VenueHallFloor floors = venueHallMapper.toFloors(floorDTO, hall);
            hall.getFloorList().add(floors);
            processSection(floors, floorDTO.getSection());
        });

    }

    private void processSection(VenueHallFloor floors , List<VenueHallSectionRequest> sectionList) {
        sectionList.forEach(sectionDTO -> {
            VenueHallSection sections = venueHallMapper.toSections(floors, sectionDTO);
            floors.getSections().add(sections);
            processRow(sections, sectionDTO.getRows());
        });
    }

    private void processRow(VenueHallSection sections , List<VenueHallRowRequest> rowList) {
        rowList.forEach(rowDTO -> {
            VenueHallRow rows = venueHallMapper.toRows(sections, rowDTO);
            sections.getRows().add(rows);
            processSeats(rows, rowDTO.getSeats());
        });
    }

    private void processSeats(VenueHallRow rows, List<VenueHallSeatRequest> seatList) {
        seatList.forEach(seatDTO -> {
            Integer startNum = seatDTO.getStartSeatNumber();
            Integer endNum = seatDTO.getEndSeatNumber();

            IntStream.rangeClosed(startNum, endNum).mapToObj(seatNumber ->
                            venueHallMapper.toSeats(rows, seatDTO, seatNumber, startNum ,endNum))
                                .forEach(seat -> rows.getSeats().add(seat));
        });
    }

}
