package com.yigalaxy.blog.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AdminSessionService sessions;

    public AuthController(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var session = sessions.login(request.username(), request.password());
        if (session == null) throw new InvalidCredentialsException();
        return new LoginResponse(session.token(), session.username(), session.expiresIn());
    }

    @PostMapping("/logout")
    public LogoutResponse logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        sessions.logout(token);
        return new LogoutResponse(true);
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) { }
    public record LoginResponse(String token, String username, long expiresIn) { }
    public record LogoutResponse(boolean success) { }
}
