package com.pointplatform.monolith.user.api;

import com.pointplatform.monolith.common.error.ApiResponse;
import com.pointplatform.monolith.common.security.LoginUser;
import com.pointplatform.monolith.user.application.AuthService;
import com.pointplatform.monolith.user.application.UserProfile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me(@LoginUser long userId) {
        return ApiResponse.ok(authService.me(userId));
    }
}
