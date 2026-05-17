package com.platform.userprofile.service;

import com.platform.userprofile.client.FileServiceClient;
import com.platform.userprofile.dto.FileUploadResponse;
import com.platform.userprofile.dto.UpdateProfileRequest;
import com.platform.userprofile.entity.Profile;
import com.platform.userprofile.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j @Service @RequiredArgsConstructor
public class UserProfileService {
    private final ProfileRepository profileRepository;
    private final FileServiceClient fileServiceClient;

    @Transactional(readOnly = true)
    public Profile getProfile(String userId) {
        return profileRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
    }

    @Transactional
    public Profile updateProfile(String userId, UpdateProfileRequest request) {
        Profile profile = profileRepository.findById(userId)
                .orElseGet(() -> Profile.builder().id(userId).build());
        if (request.getFullName() != null) profile.setFullName(request.getFullName());
        if (request.getBio() != null) profile.setBio(request.getBio());
        if (request.getPhone() != null) profile.setPhone(request.getPhone());
        if (request.getAddress() != null) profile.setAddress(request.getAddress());
        return profileRepository.save(profile);
    }

    @Transactional
    public Profile uploadAvatar(String userId, MultipartFile file) {
        try {
            var response = fileServiceClient.upload(file, userId);
            FileUploadResponse data = response.getData();
            Profile profile = profileRepository.findById(userId)
                    .orElseGet(() -> Profile.builder().id(userId).build());
            profile.setAvatarUrl(data.getUrl());
            return profileRepository.save(profile);
        } catch (Exception e) {
            log.error("Avatar upload failed for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Avatar upload failed", e);
        }
    }
}
