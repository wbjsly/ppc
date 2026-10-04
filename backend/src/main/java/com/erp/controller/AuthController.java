package com.erp.controller;

import com.erp.common.R;
import com.erp.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> loginReq) {
        return R.ok(authService.login(loginReq.get("username"), loginReq.get("password")));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        return R.ok();
    }

    @GetMapping("/info")
    public R<Map<String, Object>> getUserInfo(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return R.ok(authService.getUserInfo(authHeader));
    }
}
