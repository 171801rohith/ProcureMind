package com.procuremind.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A fixed authorization role. Rows are seeded by Flyway migration {@code V2__seed_roles.sql}
 * ({@code ADMIN} = 1, {@code ANALYST} = 2, {@code VIEWER} = 3) and are never created at runtime.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "name", nullable = false, unique = true, length = 30)
    private String name;
}
