package madacode.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in Docker integration coverage for the Phase-1 container backend.
 *
 * <p>Run with {@code MADA_EVAL_DOCKER_INTEGRATION=1 ./mvnw -Dtest=DockerAttemptExecutorIntegrationTest test}.
 */
@EnabledIfEnvironmentVariable(named = "MADA_EVAL_DOCKER_INTEGRATION", matches = "1")
class DockerAttemptExecutorIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void builtImageEntrypointRoundTripsOutcomeAndReapsBackgroundProcess() throws Exception {
        ProcessSupervisor supervisor = new ProcessSupervisor();
        ProcessSupervisor.Outcome packageOutcome = supervisor.run(
                List.of("./mvnw", "package", "-DskipTests"),
                Path.of("").toAbsolutePath(),
                Duration.ofMinutes(3),
                512 * 1024);
        assertTrue(packageOutcome.succeeded(), packageOutcome.output());

        String image = "mada-eval-it:" + Long.toUnsignedString(System.nanoTime());
        ProcessSupervisor.Outcome buildOutcome = supervisor.run(
                List.of("docker", "build", "-t", image, "-f", "eval/docker/Dockerfile", "."),
                Path.of("").toAbsolutePath(),
                Duration.ofMinutes(5),
                512 * 1024);
        assertTrue(buildOutcome.succeeded(), buildOutcome.output());
        try {
            EvalCaseLoader.LoadedCase loaded = loadedCase("docker-it-background");
            DockerAttemptExecutor executor = new DockerAttemptExecutor(
                    null,
                    "scorer-fp",
                    "docker",
                    image,
                    List.of(),
                    "none-it");
            EvalRunner runner = new EvalRunner(
                    null,
                    ScorerPipeline.of(new VerifyScriptScorer()),
                    executor,
                    AttemptArtifactWriter.NOOP);

            EvalCaseReport report = runner.runCase(loaded);

            String detail = report.attempts().isEmpty() ? "" : report.attempts().getFirst().detail();
            assertEquals(EvalCaseReport.GateVerdict.PASS, report.gateVerdict(), detail);
            EvalResult attempt = report.attempts().getFirst();
            assertEquals(EvalResult.ExecutionStatus.COMPLETED, attempt.executionStatus());
            assertFalse(report.manifest().trustedMeasurement());
            assertTrue(report.manifest().networkPolicy().contains("egress=unobserved"));

            Path processWorkspace = tempDir.resolve("process-reap-workspace");
            Files.createDirectories(processWorkspace);
            ProcessSupervisor.Outcome reapProbe = supervisor.run(
                    List.of(
                            "docker", "run", "--rm",
                            "--entrypoint", "sh",
                            "-v", processWorkspace.toAbsolutePath() + ":/workspace:rw",
                            "-w", "/workspace",
                            image,
                            "-c",
                            "nohup sh -c 'sleep 1; echo late > late-mutation.txt' >/dev/null 2>&1 &"),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(30),
                    64 * 1024);
            assertTrue(reapProbe.succeeded(), reapProbe.output());
            Thread.sleep(1500);
            assertFalse(Files.exists(processWorkspace.resolve("late-mutation.txt")));
        } finally {
            supervisor.run(
                    List.of("docker", "image", "rm", "-f", image),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(30),
                    64 * 1024);
        }
    }

    @Test
    void egressProxySidecarForwardsProviderTrafficAndRecordsObservedReport() throws Exception {
        ProcessSupervisor supervisor = new ProcessSupervisor();
        ProcessSupervisor.Outcome packageOutcome = supervisor.run(
                List.of("./mvnw", "package", "-DskipTests"),
                Path.of("").toAbsolutePath(),
                Duration.ofMinutes(3),
                512 * 1024);
        assertTrue(packageOutcome.succeeded(), packageOutcome.output());

        String image = "mada-eval-proxy-it:" + Long.toUnsignedString(System.nanoTime());
        ProcessSupervisor.Outcome buildOutcome = supervisor.run(
                List.of("docker", "build", "-t", image, "-f", "eval/docker/Dockerfile", "."),
                Path.of("").toAbsolutePath(),
                Duration.ofMinutes(5),
                512 * 1024);
        assertTrue(buildOutcome.succeeded(), buildOutcome.output());
        String providerNetwork = "mada-eval-provider-it-" + Long.toUnsignedString(System.nanoTime());
        String providerContainer = "mada-eval-provider-it-" + Long.toUnsignedString(System.nanoTime());
        try {
            ProcessSupervisor.Outcome networkOutcome = supervisor.run(
                    List.of("docker", "network", "create", providerNetwork),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(30),
                    64 * 1024);
            assertTrue(networkOutcome.succeeded(), networkOutcome.output());
            ProcessSupervisor.ManagedProcess provider = supervisor.start(
                    List.of(
                            "docker", "run", "--rm",
                            "--name", providerContainer,
                            "--network", providerNetwork,
                            "--network-alias", "provider",
                            "--entrypoint", "sh",
                            image,
                            "-c",
                            "while true; do { printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nOK'; } | nc -l -p 8090; done"),
                    Path.of("").toAbsolutePath(),
                    64 * 1024,
                    Map.of());
            try {
                Thread.sleep(1000);
                DockerEgressProxySession proxy = new DockerEgressProxySession(
                        "docker",
                        image,
                        List.of(),
                        Path.of("").toAbsolutePath(),
                        List.of(new DockerEgressProxyConfigJson.ProviderRouteJson(
                                0,
                                "mock-provider",
                                "http://provider:8090/v1",
                                "MADA_EVAL_PROXY_IT_TOKEN")),
                        Map.of("MADA_EVAL_PROXY_IT_TOKEN", "sentinel-proxy-token"),
                        List.of("sentinel-proxy-token"));
                try {
                    ProcessSupervisor.Outcome connectProxyToProvider = supervisor.run(
                            List.of("docker", "network", "connect", providerNetwork, proxy.containerName()),
                            Path.of("").toAbsolutePath(),
                            Duration.ofSeconds(30),
                            64 * 1024);
                    assertTrue(connectProxyToProvider.succeeded(), connectProxyToProvider.output());
                    ProcessSupervisor.Outcome curl = supervisor.run(
                            List.of(
                                    "docker", "run", "--rm",
                                    "--network", proxy.networkName(),
                                    "--entrypoint", "sh",
                                    image,
                                    "-c",
                                    "curl -fsS http://mada-egress-proxy:8080/provider/0/messages"),
                            Path.of("").toAbsolutePath(),
                            Duration.ofSeconds(30),
                            64 * 1024);
                    assertTrue(curl.succeeded(), curl.output());
                    assertEquals("OK", curl.output().strip());
                    madacode.governance.EgressReport report = proxy.egressReport();
                    assertEquals(madacode.governance.EgressObservation.OBSERVED, report.observation());
                    assertTrue(report.events().stream()
                            .anyMatch(event -> event.detail().contains("kind=provider-api")
                                    && !event.blocked()));
                    assertTrue(report.events().stream()
                            .noneMatch(event -> event.detail().contains("sentinel-proxy-token")));
                } finally {
                    proxy.close();
                }
            } finally {
                provider.close();
            }
        } finally {
            supervisor.run(
                    List.of("docker", "rm", "-f", providerContainer),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(30),
                    64 * 1024);
            supervisor.run(
                    List.of("docker", "network", "rm", providerNetwork),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(30),
                    64 * 1024);
            supervisor.run(
                    List.of("docker", "image", "rm", "-f", image),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(30),
                    64 * 1024);
        }
    }

    private EvalCaseLoader.LoadedCase loadedCase(String id) throws Exception {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir.resolve("workspace"));
        Files.writeString(dir.resolve("workspace/answer.txt"), "OK\n");
        Files.writeString(dir.resolve("verify.sh"), """
                #!/usr/bin/env bash
                set -eu
                test -f answer.txt
                ! test -f late-mutation.txt
                grep -q OK answer.txt
                """);
        Files.writeString(dir.resolve("case.json"), """
                {
                  "id": "%s",
                  "mode": "common",
                  "permissionMode": "default",
                  "capabilities": ["selftest"],
                  "instruction": "do nothing",
                  "samples": 1,
                  "expectedVerdict": "PASS",
                  "timeoutSeconds": 5,
                  "verifyTimeoutSeconds": 5
                }
                """.formatted(id));
        return new EvalCaseLoader(dir.getParent()).loadAll().stream()
                .filter(loaded -> loaded.evalCase().id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
