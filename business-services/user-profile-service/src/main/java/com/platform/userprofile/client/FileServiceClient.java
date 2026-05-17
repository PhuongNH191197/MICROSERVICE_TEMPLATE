package com.platform.userprofile.client;

import com.platform.common.dto.ApiResponse;
import com.platform.userprofile.dto.FileUploadResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@FeignClient(name = "file-service", path = "/api/files")
public interface FileServiceClient {
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<FileUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader("X-User-Id") String userId);
}
