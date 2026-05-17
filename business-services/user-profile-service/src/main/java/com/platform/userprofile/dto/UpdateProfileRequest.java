package com.platform.userprofile.dto;
import lombok.Data;
@Data
public class UpdateProfileRequest {
    private String fullName;
    private String bio;
    private String phone;
    private String address;
}
