package com.platform.audio.exception;
public class JobLimitExceededException extends RuntimeException {
    public JobLimitExceededException(String message) { super(message); }
}
