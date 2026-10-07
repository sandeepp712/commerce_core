package com.commercecore.backend.auth.api;

import com.commercecore.backend.auth.api.dto.*;
import com.commercecore.backend.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private  static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register( @Valid  @RequestBody RegisterRequest req) {
        RegisterResponse response=authService.register(req.email(), req.username(),req.password());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid  @RequestBody LoginRequest req,
                                               @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                               HttpServletRequest httpReq) {
        LoginResponse response=authService.login(req.email(), req.password(),userAgent,httpReq.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid  @RequestBody RefreshRequest req,
                                                 HttpServletRequest httpReq) {
        LoginResponse response = authService.refreshToken(req.refreshToken(),httpReq.getRemoteAddr());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.ok().build();
    }
}