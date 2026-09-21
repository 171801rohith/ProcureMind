package com.procuremind.ai_service.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Read-only projection of contract-service's {@code contracts} table.
 *
 * <p>ai-service and contract-service point at the same physical Postgres database
 * ({@code procuremind_db}). The {@code contracts} table's DDL is owned by history (ai-service's
 * own {@code V1__init_schema.sql} originally created it; see finding #16 in
 * {@code ARCHITECTURE_REVIEW.md}), but contract-service is the sole writer per the
 * architecture review's explicit decision.
 *
 * <p>This entity exists solely so ai-service can join against vendor/file identity
 * ({@code vendor_name}, {@code filename}) when building dashboard/analysis DTOs — it does
 * <b>not</b> ship its own Flyway migration (the table already exists via history) and no
 * ai-service code path may call {@code save}/{@code delete} on its repository. Only the
 * columns this service actually reads are mapped.
 */
@Entity
@Table(name = "contracts")
@Getter
@Setter
@NoArgsConstructor
public class ContractRef {

    @Id
    private UUID id;

    private String filename;

    private String vendorName;
}
