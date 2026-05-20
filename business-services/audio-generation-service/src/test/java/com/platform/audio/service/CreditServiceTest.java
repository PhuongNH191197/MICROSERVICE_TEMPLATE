package com.platform.audio.service;
import com.platform.audio.entity.UserCredit;
import com.platform.audio.exception.InsufficientCreditException;
import com.platform.audio.repository.CreditTransactionRepository;
import com.platform.audio.repository.UserCreditRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreditServiceTest {
    @Mock UserCreditRepository creditRepo;
    @Mock CreditTransactionRepository txRepo;
    @InjectMocks CreditService creditService;

    @Test
    @DisplayName("deduct with sufficient balance succeeds")
    void deduct_sufficientBalance_succeeds() {
        UserCredit credit = UserCredit.builder().userId("user1").balance(5).build();
        when(creditRepo.findByUserIdForUpdate("user1")).thenReturn(Optional.of(credit));
        when(creditRepo.save(any())).thenReturn(credit);

        assertThatNoException().isThrownBy(() -> creditService.deductCredit("user1", UUID.randomUUID()));
        assertThat(credit.getBalance()).isEqualTo(4);
    }

    @Test
    @DisplayName("deduct with zero balance throws InsufficientCreditException")
    void deduct_zeroBalance_throws() {
        UserCredit credit = UserCredit.builder().userId("user1").balance(0).build();
        when(creditRepo.findByUserIdForUpdate("user1")).thenReturn(Optional.of(credit));

        assertThatThrownBy(() -> creditService.deductCredit("user1", UUID.randomUUID()))
            .isInstanceOf(InsufficientCreditException.class)
            .hasMessageContaining("Insufficient");
    }

    @Test
    @DisplayName("deduct with no account throws InsufficientCreditException")
    void deduct_noAccount_throws() {
        when(creditRepo.findByUserIdForUpdate("user1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> creditService.deductCredit("user1", UUID.randomUUID()))
            .isInstanceOf(InsufficientCreditException.class);
    }

    @Test
    @DisplayName("refund adds balance back")
    void refund_addsBalance() {
        UserCredit credit = UserCredit.builder().userId("user1").balance(3).build();
        when(creditRepo.findByUserIdForUpdate("user1")).thenReturn(Optional.of(credit));
        when(creditRepo.save(any())).thenReturn(credit);

        creditService.refundCredit("user1", UUID.randomUUID());
        assertThat(credit.getBalance()).isEqualTo(4);
    }
}
