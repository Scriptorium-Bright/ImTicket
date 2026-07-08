package org.example.ticket.performance.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Organizer;
import org.example.ticket.member.repository.OrganizerRepository;
import org.example.ticket.performance.response.PerformanceDetailsResponse;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.request.PerformanceDetailRequest;
import org.example.ticket.performance.response.PerformanceOverviewResponse;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.TimeUnit;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceService {
    private static final String DETAILS_REQUESTS_METRIC = "imticket.performance.details.requests";
    private static final String DETAILS_DURATION_METRIC = "imticket.performance.details.duration";
    private static final String CACHE_WRITE_METRIC = "imticket.performance.details.cache.writes";
    private static final String DETAILS_CACHE_NAME = "performanceDetails";

    private final PerformanceRepository performanceRepository;
    private final OrganizerRepository organizerRepository;
    private final FileService fileService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    public final static int CACHE_TIMEOUT = 10;
    public final static TimeUnit MINUTES = TimeUnit.MINUTES;

    @Transactional
    public Long registerPerformance(String walletAddress, PerformanceDetailRequest detailsRequest, MultipartFile file) throws IOException {

        Organizer organizer = organizerRepository.findByMemberWalletAddress(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("공연 등록 권한이 없습니다."));
        String dbFilePath = fileService.saveImages(file);

        Performance performance = Performance.builder()
                .ageLimit(detailsRequest.getAge())
                .description(detailsRequest.getDescription())
                .title(detailsRequest.getTitle())
                .imageUrl(dbFilePath)
                .startDate(detailsRequest.getStartDate())
                .endDate(detailsRequest.getEndDate())
                .venueType(detailsRequest.getVenueType())
                .build();
        organizer.addPerformance(performance);

        return performanceRepository.save(performance).getId();
    }

    @Transactional(readOnly = true)
    public PerformanceDetailsResponse viewPerformanceDetails(Long pathId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            PerformanceDetailsResponse response = loadPerformanceDetailsFromDb(pathId);
            recordDetailsRequest("direct", "bypass", sample);
            return response;
        } catch (RuntimeException e) {
            recordDetailsRequest("direct", "error", sample);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<PerformanceOverviewResponse> viewPerformanceIntro() {
        return performanceRepository.findByIntro();
    }

    public PerformanceDetailsResponse viewPerformanceDetailsCached(Long pathId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String key = "performance:details:" + pathId;
        try {
            PerformanceDetailsResponse cached = (PerformanceDetailsResponse) redisTemplate.opsForValue().get(key);

            if (cached != null) {
                log.info("Cache Hit for performance id: {}", pathId);
                recordDetailsRequest("cache", "hit", sample);
                return cached;
            }

            log.info("Cache Miss for performance id: {}", pathId);
            PerformanceDetailsResponse response = loadPerformanceDetailsFromDb(pathId);
            redisTemplate.opsForValue().set(key, response, CACHE_TIMEOUT, MINUTES);
            meterRegistry.counter(CACHE_WRITE_METRIC, "cache", DETAILS_CACHE_NAME).increment();
            recordDetailsRequest("cache", "miss", sample);
            return response;
        } catch (RuntimeException e) {
            recordDetailsRequest("cache", "error", sample);
            throw e;
        }
    }

    private PerformanceDetailsResponse loadPerformanceDetailsFromDb(Long pathId) {
        Performance performanceDetails = performanceRepository.findById(pathId)
                .orElseThrow(() -> new EntityNotFoundException("해당 공연을 찾을 수 없습니다."));
        return PerformanceDetailsResponse.from(performanceDetails);
    }

    private void recordDetailsRequest(String access, String result, Timer.Sample sample) {
        meterRegistry.counter(
                DETAILS_REQUESTS_METRIC,
                "cache", DETAILS_CACHE_NAME,
                "access", access,
                "result", result
        ).increment();

        sample.stop(meterRegistry.timer(
                DETAILS_DURATION_METRIC,
                "cache", DETAILS_CACHE_NAME,
                "access", access,
                "result", result
        ));
    }
}
