package com.procuremind.ai_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "contract_analysis")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private UUID contractId;

    private Double riskScore;

    @Column(columnDefinition = "TEXT")
    private String recommendation;

    private String status;
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AnalysisRisk> risks;

}
