package com.platform.userprofile.controller;
import com.platform.common.dto.ApiResponse;
import com.platform.userprofile.dto.UpdateProfileRequest;
import com.platform.userprofile.entity.Profile;
import com.platform.userprofile.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/users") @RequiredArgsConstructor
public class UserProfileController {
    private final UserProfileService userProfileService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<Profile>> getProfile(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ApiResponse.success(userProfileService.getProfile(userId)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<Profile>> updateProfile(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userProfileService.updateProfile(userId, request)));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Profile>> uploadAvatar(
            @RequestHeader("X-User-Id") String userId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(userProfileService.uploadAvatar(userId, file)));
    }
}
