package com.platform.audio.repository;
import com.platform.audio.entity.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {
}
