package com.platform.file.repository;
import com.platform.file.entity.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {
    List<FileRecord> findByUserIdAndDeletedFalse(String userId);
    Optional<FileRecord> findByIdAndDeletedFalse(UUID id);
}
