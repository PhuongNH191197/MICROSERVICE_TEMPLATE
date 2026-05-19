package com.platform.file.controller;
import com.platform.common.dto.ApiResponse;
import com.platform.file.dto.*;
import com.platform.file.service.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController @RequestMapping("/api/files") @RequiredArgsConstructor
public class FileController {
    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        return ResponseEntity.ok(ApiResponse.success(fileStorageService.upload(file, userId)));
    }

    @PostMapping("/presigned-url")
    public ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
            @RequestBody @Valid PresignedUrlRequest request,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        return ResponseEntity.ok(ApiResponse.success(fileStorageService.createPresignedPutUrl(request, userId)));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<FileMetadataResponse>> confirmUpload(
            @RequestBody @Valid ConfirmUploadRequest request,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        return ResponseEntity.ok(ApiResponse.success(fileStorageService.confirmUpload(request, userId)));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Void> getFile(@PathVariable String fileId) throws Exception {
        String url = fileStorageService.getPresignedUrl(fileId);
        return ResponseEntity.status(302).header("Location", url).build();
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String fileId,
            @RequestHeader("X-User-Id") String userId) {
        fileStorageService.delete(fileId, userId);
        return ResponseEntity.ok(ApiResponse.success("File deleted", null));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<FileMetadataResponse>>> getUserFiles(
            @PathVariable String userId,
            @RequestHeader("X-User-Id") String requestingUserId) {
        if (!requestingUserId.equals(userId))
            throw new IllegalArgumentException("Access denied");
        List<FileMetadataResponse> files = fileStorageService.getUserFiles(userId);
        return ResponseEntity.ok(ApiResponse.success(files));
    }
}
