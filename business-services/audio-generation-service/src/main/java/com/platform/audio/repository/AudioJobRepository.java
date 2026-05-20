package com.platform.audio.repository;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.enums.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AudioJobRepository extends JpaRepository<AudioJob, UUID> {
    Optional<AudioJob> findByIdAndUserId(UUID id, String userId);
    List<AudioJob> findByStatus(JobStatus status);
    long countByUserIdAndStatusIn(String userId, List<JobStatus> statuses);
    Page<AudioJob> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(String userId, Pageable pageable);
}
