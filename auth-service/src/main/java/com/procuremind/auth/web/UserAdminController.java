package com.procuremind.auth.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.procuremind.auth.dto.UserDtos;
import com.procuremind.auth.service.UserAdminService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only user administration (Phase 8, plan section 12). Protected by both the
 * {@code /api/users/**} filter chain in {@code ApiSecurityConfig} and the method-level
 * {@code @PreAuthorize} below, so the rule survives any future matcher change.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping
    public ResponseEntity<List<UserDtos.UserResponse>> listUsers() {
        return ResponseEntity.ok(userAdminService.listUsers());
    }

    @PostMapping
    public ResponseEntity<UserDtos.UserResponse> createUser(@Valid @RequestBody UserDtos.CreateUserRequest request) {
        UserDtos.UserResponse created = userAdminService.createUser(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        return userAdminService.deleteUser(id) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
