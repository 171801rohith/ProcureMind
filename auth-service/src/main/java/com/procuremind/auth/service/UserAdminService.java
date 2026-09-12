package com.procuremind.auth.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.procuremind.auth.dto.UserDtos;
import com.procuremind.auth.entity.Role;
import com.procuremind.auth.entity.User;
import com.procuremind.auth.repository.RoleRepository;
import com.procuremind.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration behind the ADMIN-only {@code /api/users} API (Phase 8).
 * Roles are reference data seeded by Flyway; this service never creates them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserDtos.UserResponse> listUsers() {
        return userRepository.findAll().stream().map(UserAdminService::toResponse).toList();
    }

    @Transactional
    public UserDtos.UserResponse createUser(UserDtos.CreateUserRequest request) {
        String username = request.username().trim();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }

        Set<String> requested = (request.roles() == null || request.roles().isEmpty())
                ? Set.of("VIEWER")
                : request.roles();

        Set<Role> roles = requested.stream()
                .map(name -> name.trim().toUpperCase(Locale.ROOT))
                .map(name -> roleRepository.findByName(name)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + name)))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .email(request.email())
                .enabled(true)
                .roles(roles)
                .build();

        User saved = userRepository.save(user);
        log.info("Created user '{}' with roles {}", saved.getUsername(), requested);
        return toResponse(saved);
    }

    @Transactional
    public boolean deleteUser(UUID id) {
        return userRepository.findById(id).map(user -> {
            userRepository.delete(user);
            log.info("Deleted user '{}'", user.getUsername());
            return true;
        }).orElse(false);
    }

    private static UserDtos.UserResponse toResponse(User user) {
        List<String> roles = user.getRoles() == null ? List.of()
                : user.getRoles().stream().map(Role::getName).sorted().toList();
        return new UserDtos.UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.isEnabled(), roles);
    }
}
