package com.platform.file.controller;
import com.platform.common.dto.ApiResponse;
import com.platform.file.dto.FileUploadResponse;
import com.platform.file.entity.FileRecord;
import com.platform.file.repository.FileRecordRepository;
import com.platform.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController @RequestMapping("/api/files") @RequiredArgsConstructor
public class FileController {
    private final FileStorageService fileStorageService;
    private final FileRecordRepository fileRecordRepository;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        return ResponseEntity.ok(ApiResponse.success(fileStorageService.upload(file, userId)));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Void> getFile(@PathVariable String fileId) throws Exception {
        String url = fileStorageService.getPresignedUrl(fileId);
        return ResponseEntity.status(302).header("Location", url).build();
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String fileId) {
        fileStorageService.delete(fileId);
        return ResponseEntity.ok(ApiResponse.success("File deleted", null));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<FileRecord>>> getUserFiles(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success(fileRecordRepository.findByUserIdAndDeletedFalse(userId)));
    }
}
