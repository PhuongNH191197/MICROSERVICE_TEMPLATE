package com.platform.audio.repository;
import com.platform.audio.entity.UserCredit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UserCreditRepository extends JpaRepository<UserCredit, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM UserCredit c WHERE c.userId = :userId")
    Optional<UserCredit> findByUserIdForUpdate(@Param("userId") String userId);
}
