package com.platform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @Email @NotBlank private String email;
    @NotBlank @Size(min = 8) private String password;
    @NotBlank private String fullName;
}
