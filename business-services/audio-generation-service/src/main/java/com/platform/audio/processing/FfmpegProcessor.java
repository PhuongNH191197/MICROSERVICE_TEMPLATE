package com.platform.audio.processing;
import com.platform.audio.exception.AudioProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Component @Slf4j
public class FfmpegProcessor {

    public Path cutSegment(Path input, int startMs, int endMs) throws IOException, InterruptedException {
        Path out = Files.createTempFile("cut_", ".mp3");
        run("ffmpeg", "-y", "-i", input.toString(),
            "-ss", msToSec(startMs), "-to", msToSec(endMs),
            "-ar", "48000", "-ac", "2", "-b:a", "128k", out.toString());
        log.info("Cut segment {}ms-{}ms -> {}", startMs, endMs, out);
        return out;
    }

    public List<Path> mix3Versions(Path voicePath, Path musicPath) throws IOException, InterruptedException {
        int[][] configs = {{-10, -20}, {-10, -18}, {-12, -16}};
        List<Path> results = new ArrayList<>();
        for (int[] cfg : configs) {
            Path out = Files.createTempFile("mix_", ".mp3");
            String voiceFilter = "loudnorm=I=" + cfg[0] + ":LRA=11:TP=-1.5";
            String musicFilter = "loudnorm=I=" + cfg[1] + ":LRA=11:TP=-1.5";
            String filterComplex = String.format(
                "[0:a]%s[v];[1:a]%s[m];[m][v]amix=inputs=2:duration=first[out];" +
                "[out]afade=t=in:st=0:d=0.3,afade=t=out:st=49:d=0.5[final]",
                voiceFilter, musicFilter);
            run("ffmpeg", "-y",
                "-i", voicePath.toString(),
                "-i", musicPath.toString(),
                "-filter_complex", filterComplex,
                "-map", "[final]",
                "-ar", "48000", "-ac", "2", "-b:a", "128k", out.toString());
            results.add(out);
        }
        return results;
    }

    public Path padToMinDuration(Path input, int minSeconds) throws IOException, InterruptedException {
        Path out = Files.createTempFile("padded_", ".mp3");
        run("ffmpeg", "-y", "-i", input.toString(),
            "-af", "apad=pad_dur=" + minSeconds,
            "-t", String.valueOf(minSeconds),
            "-ar", "48000", "-ac", "2", "-b:a", "128k", out.toString());
        return out;
    }

    public void deleteSilently(Path path) {
        try { if (path != null) Files.deleteIfExists(path); }
        catch (IOException e) { log.warn("Failed to delete temp file {}: {}", path, e.getMessage()); }
    }

    private void run(String... cmd) throws IOException, InterruptedException {
        log.debug("Running: {}", String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            log.error("ffmpeg failed (exit={}): {}", exit, output);
            throw new AudioProcessingException("ffmpeg failed exit=" + exit);
        }
    }

    private String msToSec(int ms) { return String.format(Locale.US, "%.3f", ms / 1000.0); }
}
