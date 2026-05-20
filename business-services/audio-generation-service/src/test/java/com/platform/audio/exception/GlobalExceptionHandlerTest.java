package com.platform.audio.exception;

import com.platform.common.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleInsufficientCredit - returns 429 with message")
    void handleInsufficientCredit_returns429() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleInsufficientCredit(new InsufficientCreditException("Insufficient credits"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Insufficient credits");
    }

    @Test
    @DisplayName("handleJobLimit - returns 429 with message")
    void handleJobLimit_returns429() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleJobLimit(new JobLimitExceededException("Max 5 concurrent jobs"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("5");
    }

    @Test
    @DisplayName("handleVocalDetected - returns 400 with message")
    void handleVocalDetected_returns400() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleVocalDetected(new VocalDetectedException("Music contains vocals"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("vocal");
    }

    @Test
    @DisplayName("handleNotFound - returns 404 with message")
    void handleNotFound_returns404() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleNotFound(new AudioJobNotFoundException("job-123"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("handleIllegalArg - returns 400 with message")
    void handleIllegalArg_returns400() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleIllegalArg(new IllegalArgumentException("Bad value"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Bad value");
    }

    @Test
    @DisplayName("handleValidation - with field error - returns 400 with field name")
    void handleValidation_withFieldError_returns400WithFieldMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("aiGenerateRequest", "genre", "must not be null");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldError()).thenReturn(fieldError);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("genre");
    }

    @Test
    @DisplayName("handleValidation - no field error - returns 400 with generic message")
    void handleValidation_noFieldError_returns400WithGenericMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldError()).thenReturn(null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Validation failed");
    }

    @Test
    @DisplayName("handleGeneral - returns 500 with generic message")
    void handleGeneral_returns500() {
        ResponseEntity<ApiResponse<Void>> response =
            handler.handleGeneral(new RuntimeException("Unexpected failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
    }
}
