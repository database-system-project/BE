package com.databasesystem.hospital.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signup(@Valid @RequestBody Signup request) { return service.signup(request); }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody Login request) { return service.login(request); }

    public record Signup(@NotBlank @Size(max = 50) String loginId,
                         @NotBlank @Size(min = 8, max = 72) String password,
                         @NotBlank @Size(max = 50) String name,
                         @NotNull LocalDate birthDate, @NotBlank String gender,
                         @NotBlank String phone, @NotNull @AssertTrue Boolean privacyAgreed) { }

    public record Login(@NotBlank @Size(max = 50) String loginId,
                        @NotBlank @Size(max = 72) String password) { }
}
