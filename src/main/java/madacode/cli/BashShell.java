package madacode.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public final class BashShell {

    private BashShell() {}

    public static Result execute(String command, Path workingDirectory) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/sh", "-lc", command == null ? "" : command));
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }
        Process process = pb.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        return new Result(
                new String(stdout, StandardCharsets.UTF_8),
                new String(stderr, StandardCharsets.UTF_8),
                exitCode);
    }

    public record Result(String stdout, String stderr, int exitCode) {}
}
