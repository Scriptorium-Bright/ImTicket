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

        Organizer organizer = organizerRepository.findByMemberWalletAddressIgnoreCase(walletAddress)
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

    /**
     * 생각해보아야 할 것, viewPerformanceIntro에 대해 매번 새 공연이 올라오게 될 경우 cache에 계속해서 등록을 하게 되어야 함, 그렇다면 어차피 밀리는거 cache에 등록하는건
     * 조회수를 측정해서 조회수 상위권 N개 공연에 대해 cache에 등록하고 보여지게 하는 것이 더 낫지않나라는 생각
     * 조회수가 많은 공연이다 == 사람들이 해당 공연 페이지 클릭을 많이 한다 == 즉 화면을 불러오는 시간이 다른 것들보다 많다.
     *
     * 아니 그러면 모든 공연들에 대해 cache를 등록하면 되는거 아닌가요?
     * 일단 기본적으로 cache는 memory이다. 왜 빠르겠나? 생각을 해보면 당연히 메모리니까 빠르겠지 근데 메모리는 volatile한 성질을 가지고있다. 즉 휘발성, 뭐 휘발성인 것도 있지만 다 등록을 하면 안 되는 이유
     * 일단 상식적으로 생각을 해보면 휘발성이라는건, 사라진다는거고 사라지면 다시 적재를 해야한다는거고 그러면 비용이 무쟈게 많이 들거고 다 담을 수 있을만큼 용량이 많지도않고, 되는만큼 담는다고 해서 되는게 아님
     * 애초에
     * 우리는 redis cache를 통해 / (여기서 inmemory cache들을 비교해보고 쓰는 방향도 좋을듯)
     * 조회를 하게 됨, 근데 만약 조회가 자주 안되는 공연들을 메모리에 올려놓으면, 어차피 사용도 잘 안돼서 Scheduling에 의해 정리될탠데 그것도 비용 낭비임
     * 그럼 아까 말했듯 상위 N개의 공연에 대해 캐시에 올려놓는 것이 가장 효율적
     * 여기서 고민해야하는게 redis의 Zset 과 cache stamped에 대한 것
     * @return
     */
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
