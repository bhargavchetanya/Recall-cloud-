package dev.recall.web;

import dev.recall.service.AuthService;
import dev.recall.web.dto.AuthDtos.AuthResponse;
import dev.recall.web.dto.AuthDtos.JoinRequest;
import dev.recall.web.dto.AuthDtos.LoginRequest;
import dev.recall.web.dto.AuthDtos.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest r) {
        return AuthResponse.from(auth.register(r.teamName(), r.email(), r.password(), r.displayName()));
    }

    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse join(@Valid @RequestBody JoinRequest r) {
        return AuthResponse.from(auth.join(r.inviteCode(), r.email(), r.password(), r.displayName()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) {
        return AuthResponse.from(auth.login(r.email(), r.password()));
    }
}
