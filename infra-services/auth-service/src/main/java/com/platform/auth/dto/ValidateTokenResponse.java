package com.platform.auth.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data @Builder
public class ValidateTokenResponse {
    private String userId;
    private String email;
    private List<String> roles;
    private boolean valid;
}
