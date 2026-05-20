package com.platform.audio.service;
import com.platform.audio.entity.CreditTransaction;
import com.platform.audio.entity.UserCredit;
import com.platform.audio.exception.InsufficientCreditException;
import com.platform.audio.repository.CreditTransactionRepository;
import com.platform.audio.repository.UserCreditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service @RequiredArgsConstructor @Slf4j
public class CreditService {
    private final UserCreditRepository creditRepo;
    private final CreditTransactionRepository txRepo;

    @Transactional
    public void deductCredit(String userId, UUID jobId) {
        UserCredit credit = creditRepo.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new InsufficientCreditException("No credit account for user: " + userId));
        if (credit.getBalance() < 1)
            throw new InsufficientCreditException("Insufficient credits (balance=0)");
        credit.setBalance(credit.getBalance() - 1);
        credit.setUpdatedAt(Instant.now());
        creditRepo.save(credit);
        txRepo.save(CreditTransaction.builder()
            .userId(userId).amount(-1).type("DEBIT_GENERATION").jobId(jobId).build());
        log.info("Deducted 1 credit userId={} newBalance={}", userId, credit.getBalance());
    }

    @Transactional
    public void refundCredit(String userId, UUID jobId) {
        UserCredit credit = creditRepo.findByUserIdForUpdate(userId)
            .orElseGet(() -> UserCredit.builder().userId(userId).balance(0).build());
        credit.setBalance(credit.getBalance() + 1);
        credit.setUpdatedAt(Instant.now());
        creditRepo.save(credit);
        txRepo.save(CreditTransaction.builder()
            .userId(userId).amount(1).type("REFUND_CANCEL").jobId(jobId).build());
        log.info("Refunded 1 credit userId={} newBalance={}", userId, credit.getBalance());
    }

    public int getBalance(String userId) {
        return creditRepo.findById(userId).map(UserCredit::getBalance).orElse(0);
    }

    @Transactional
    public void topup(String userId, int amount) {
        UserCredit credit = creditRepo.findByUserIdForUpdate(userId)
            .orElseGet(() -> UserCredit.builder().userId(userId).balance(0).build());
        credit.setBalance(credit.getBalance() + amount);
        credit.setUpdatedAt(Instant.now());
        creditRepo.save(credit);
        txRepo.save(CreditTransaction.builder()
            .userId(userId).amount(amount).type("TOPUP").build());
        log.info("Topped up {} credits for userId={}", amount, userId);
    }
}
