package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.AnalysisRisk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AnalysisRiskRepository extends JpaRepository<AnalysisRisk, UUID> {
}
