package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.AnalysisRisk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnalysisRiskRepository extends JpaRepository<AnalysisRisk, UUID> {

    interface RiskFrequency {
        String getDescription();

        Long getFrequency();
    }

    interface RiskDistributionProjection {
        String getSeverity();

        Long getCount();
    }

    @Query("""
                    SELECT r.description AS description, COUNT(r) AS frequency
                    FROM AnalysisRisk r
                    GROUP BY r.description
                    ORDER BY frequency DESC
            """)
    List<RiskFrequency> findTopRisks();

    @Query("SELECT r.severity AS severity, COUNT(r) AS count FROM AnalysisRisk r GROUP BY r.severity")
    List<RiskDistributionProjection> getRiskSeverityDistribution();
}
