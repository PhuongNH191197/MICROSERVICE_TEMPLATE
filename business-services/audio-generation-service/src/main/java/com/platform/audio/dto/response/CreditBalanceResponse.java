package com.platform.audio.dto.response;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreditBalanceResponse {
    private String userId;
    private Integer balance;
}
