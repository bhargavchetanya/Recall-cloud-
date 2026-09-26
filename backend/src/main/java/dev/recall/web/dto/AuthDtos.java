package dev.recall.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.recall.service.AuthService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String teamName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 100) String displayName) {}

    public record JoinRequest(
            @NotBlank @Size(max = 32) String inviteCode,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 100) String displayName) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {}

    /** {@code inviteCode} is present only in the register response. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthResponse(String token, Instant expiresAt, UUID teamId, String inviteCode) {

        public static AuthResponse from(AuthService.Session s) {
            return new AuthResponse(s.token(), s.expiresAt(), s.teamId(), s.inviteCode());
        }
    }
}
