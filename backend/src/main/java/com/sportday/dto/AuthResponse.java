package com.sportday.dto;

import com.sportday.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    private String username;
    private String role;
    private String fullName;
    private Long userId;

    public static AuthResponse from(String token, User user) {
        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .userId(user.getId())
                .build();
    }
}
