package com.platform.audio.exception;
public class AudioJobNotFoundException extends RuntimeException {
    public AudioJobNotFoundException(String jobId) { super("Audio job not found: " + jobId); }
}
