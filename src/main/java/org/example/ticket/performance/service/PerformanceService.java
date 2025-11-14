package org.example.ticket.performance.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.response.PerformanceDetailsResponse;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.request.PerformanceDetailRequest;
import org.example.ticket.performance.response.PerformanceOverviewResponse;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceService {

    private final PerformanceRepository performanceRepository;
    private final FileService fileService;

    @Transactional
    public Long registerPerformance(PerformanceDetailRequest detailsRequest, MultipartFile file) throws IOException {

//        String dbFilePath = fileService.saveImages(file);

        Performance performance =
                Performance.builder()
                        .ageLimit(detailsRequest.getAge())
                        .description(detailsRequest.getDescription())
                        .title(detailsRequest.getTitle())
                        .imageUrl("hello World!")
                        .startDate(detailsRequest.getStartDate())
                        .endDate(detailsRequest.getEndDate())
                        .venueType(detailsRequest.getVenueType())
                        .build();

        return performanceRepository.save(performance).getId();
    }

    public PerformanceDetailsResponse viewPerformanceDetails(Long pathId) {
        Performance performanceDetails = performanceRepository.findByIdWithDetails(pathId).orElseThrow(() -> new EntityNotFoundException("해당 공연을 찾을 수 없습니다."));
        return PerformanceDetailsResponse.from(performanceDetails);
    }

    @Transactional(readOnly = true)
    public List<PerformanceOverviewResponse> viewPerformanceIntro() {
        return performanceRepository.findByIntro();
    }







}
