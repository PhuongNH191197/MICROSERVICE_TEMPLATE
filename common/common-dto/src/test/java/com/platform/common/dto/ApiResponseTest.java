package com.platform.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ApiResponseTest {

    @Test
    void success_withData_setsFields() {
        var response = ApiResponse.success("hello");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("hello");
        assertThat(response.getMessage()).isNull();
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void success_withMessage_setsFields() {
        var response = ApiResponse.success("created", "hello");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("created");
    }

    @Test
    void error_setsSuccessFalse() {
        var response = ApiResponse.error("something went wrong");
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("something went wrong");
        assertThat(response.getData()).isNull();
    }

    @Test
    void pageResponse_of_calculatesTotalPages() {
        var page = PageResponse.of(List.of("a", "b"), 0, 2, 5L);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.isLast()).isFalse();
    }

    @Test
    void pageResponse_singlePage_isLast() {
        var page = PageResponse.of(List.of("a"), 0, 10, 3L);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.isLast()).isTrue();
    }

    @Test
    void errorResponse_of_setsFields() {
        var err = ErrorResponse.of("ERR_001", "bad request");
        assertThat(err.getCode()).isEqualTo("ERR_001");
        assertThat(err.getMessage()).isEqualTo("bad request");
        assertThat(err.getErrors()).isNull();
    }

    @Test
    void errorResponse_withFieldErrors() {
        var fieldErrors = List.of(new ErrorResponse.FieldError("email", "invalid format"));
        var err = ErrorResponse.of("VALIDATION", "invalid", fieldErrors);
        assertThat(err.getErrors()).hasSize(1);
        assertThat(err.getErrors().get(0).getField()).isEqualTo("email");
    }
}
