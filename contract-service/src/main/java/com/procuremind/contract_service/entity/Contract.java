package com.procuremind.contract_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "contracts")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Contract {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String filename;
    private String minioObjectName;
    private String vendorName;
    private String status;
    private LocalDateTime uploadedAt;

}
