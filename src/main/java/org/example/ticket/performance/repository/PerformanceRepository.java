package org.example.ticket.performance.repository;

import io.lettuce.core.dynamic.annotation.Param;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.response.PerformanceOverviewResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {


    @Query(
            "SELECT new org.example.ticket.performance.response.PerformanceOverviewResponse(p.id, p.title, p.imageUrl, p.startDate, p.endDate) " +
                    " FROM Performance p "
    )
    List<PerformanceOverviewResponse> findByIntro();


    Optional<Performance> findById(@Param("id") Long id);

    @Query("select distinct p from Performance p " +
            "left join fetch p.seatPrices " +
            "left join fetch p.performanceTimes " +
            "where p.id=:id")
    Optional<Performance> findByIdWithDetails(Long id);


}
