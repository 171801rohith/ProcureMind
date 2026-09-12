package com.procuremind.auth.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response payloads for the ADMIN-only user administration API (Phase 8). */
public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(UUID id, String username, String email, boolean enabled, List<String> roles) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 100) String password,
            @Size(max = 255) String email,
            Set<String> roles) {
    }
}
