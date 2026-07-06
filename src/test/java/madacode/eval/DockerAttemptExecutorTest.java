package madacode.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerAttemptExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void noModelDockerBackendUsesContainerDtoAndPhaseOneManifest() throws Exception {
        EvalCaseLoader.LoadedCase loaded = loadedCase("docker-selftest");
        DockerAttemptExecutor executor = new DockerAttemptExecutor(
                null,
                "scorer-fp",
                fakeDocker(true),
                "fake-eval-image:latest",
                List.of(),
                "none-test");
        EvalRunner runner = new EvalRunner(
                null,
                ScorerPipeline.of(new VerifyScriptScorer()),
                executor,
                AttemptArtifactWriter.NOOP);

        EvalCaseReport report = runner.runCase(loaded);

        assertEquals(EvalCaseReport.GateVerdict.PASS, report.gateVerdict());
        EvalRunManifest manifest = report.manifest();
        assertEquals("docker", manifest.executionBackend());
        assertEquals("CONTAINER", manifest.isolation());
        assertEquals("HIDDEN", manifest.judgeVisibility());
        assertEquals("BLOCKED", manifest.hostAccess());
        assertEquals("ALLOWED", manifest.networkAccess());
        assertFalse(manifest.trustedMeasurement());
        assertEquals("fake-eval-image:latest", manifest.containerImage());
        assertEquals("fake-eval-image@sha256:test", manifest.containerImageDigest());
        assertEquals("none-self-test", manifest.providerConfigMaterialization());
        assertTrue(manifest.networkPolicy().contains("egress=unobserved"));
    }

    @Test
    void dockerUnavailableIsInfrastructureErrorWithoutLocalFallback() throws Exception {
        EvalCaseLoader.LoadedCase loaded = loadedCase("docker-missing");
        DockerAttemptExecutor executor = new DockerAttemptExecutor(
                null,
                "scorer-fp",
                fakeDocker(false),
                "fake-eval-image:latest",
                List.of(),
                "none-test");
        EvalRunner runner = new EvalRunner(
                null,
                ScorerPipeline.of(new VerifyScriptScorer()),
                executor,
                AttemptArtifactWriter.NOOP);

        EvalCaseReport report = runner.runCase(loaded);

        assertEquals(EvalCaseReport.GateVerdict.INFRA_ERROR, report.gateVerdict());
        assertEquals("docker", report.manifest().executionBackend());
        assertFalse(report.manifest().trustedMeasurement());
        assertTrue(report.attempts().getFirst().detail().contains("docker backend unavailable"));
    }

    @Test
    void dockerRuntimeBackendUsesInternalProxyNetworkAndKeepsTokenOutOfAgent() throws Exception {
        EvalCaseLoader.LoadedCase loaded = loadedCase("docker-provider-token");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve(".mada"));
        Files.writeString(home.resolve(".mada/providers.json"), """
                {
                  "providers": [
                    {
                      "name": "provider-a",
                      "authToken": "sentinel-token-123",
                      "baseUrl": "https://provider.example/v1",
                      "defaultModel": "model-a",
                      "models": [{"name": "model-a"}]
                    }
                  ]
                }
                """);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            DockerAttemptExecutor executor = new DockerAttemptExecutor(
                    tempDir,
                    true,
                    "scorer-fp",
                    fakeDocker(true),
                    "fake-eval-image:latest",
                    List.of(),
                    "none-test");
            EvalRunner runner = new EvalRunner(
                    null,
                    ScorerPipeline.of(new VerifyScriptScorer()),
                    executor,
                    AttemptArtifactWriter.NOOP);

            EvalCaseReport report = runner.runCase(loaded);

            assertEquals(EvalCaseReport.GateVerdict.PASS, report.gateVerdict());
            EvalRunManifest manifest = report.manifest();
            assertEquals("PROXIED", manifest.networkAccess());
            assertTrue(manifest.trustedMeasurement());
            assertTrue(manifest.networkPolicy().contains("egress=allowlist-proxy"));
            assertEquals("proxy-key-custody+container-temp-home", manifest.providerConfigMaterialization());
            String networkArgs = Files.readString(tempDir.resolve("docker-network-args.txt"));
            assertTrue(networkArgs.contains("create\n--internal\nmada-eval-egress-"));
            String proxyArgs = Files.readString(tempDir.resolve("docker-proxy-args.txt"));
            assertTrue(proxyArgs.contains("--network-alias\nmada-egress-proxy\n"));
            assertTrue(proxyArgs.contains("\n-e\nMADA_EVAL_PROXY_PROVIDER_TOKEN_0\n"));
            assertFalse(proxyArgs.contains("sentinel-token-123"));
            String args = Files.readString(tempDir.resolve("docker-run-args.txt"));
            assertTrue(args.contains("--entrypoint\nsh\n"));
            assertTrue(args.contains("--network\nmada-eval-egress-"));
            assertFalse(args.contains("MADA_EVAL_PROXY_PROVIDER_TOKEN_0"));
            assertFalse(args.contains("sentinel-token-123"));
            String verifyArgs = Files.readString(tempDir.resolve("docker-verify-args.txt"));
            assertTrue(verifyArgs.contains("--entrypoint\nsh\n"));
            assertTrue(verifyArgs.contains("--network\nnone\n"));
            String input = Files.readString(tempDir.resolve("captured-attempt.json"));
            assertTrue(input.contains("\\\"authToken\\\":\\\"proxied-provider-token\\\""));
            assertTrue(input.contains("http://mada-egress-proxy:8080/provider/0"));
            assertFalse(input.contains("sentinel-token-123"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void dockerBackendRedactsProviderTokenFromOutcome() throws Exception {
        EvalCaseLoader.LoadedCase loaded = loadedCase("docker-redact-token");
        Path home = tempDir.resolve("redact-home");
        Files.createDirectories(home.resolve(".mada"));
        Files.writeString(home.resolve(".mada/providers.json"), """
                {
                  "providers": [
                    {
                      "name": "provider-a",
                      "authToken": "sentinel-token-123",
                      "baseUrl": "https://provider.example/v1",
                      "defaultModel": "model-a",
                      "models": [{"name": "model-a"}]
                    }
                  ]
                }
                """);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DockerAttemptExecutor executor = new DockerAttemptExecutor(
                tempDir,
                true,
                "scorer-fp",
                fakeDockerWithOutcome("""
                        {
                          "schemaVersion": "spike-1",
                          "caseId": "docker-redact-token",
                          "mode": "common",
                          "executionStatus": "COMPLETED",
                          "terminalSummary": "COMPLETED sentinel-token-123",
                          "detail": "detail sentinel-token-123",
                          "finalText": "final sentinel-token-123",
                          "metrics": {
                            "controlIterations": 0,
                            "workerIterations": 0,
                            "totalIterations": 0,
                            "workerCycles": 0,
                            "toolCalls": 0,
                            "tokenUsage": {
                              "inputTokens": 0,
                              "outputTokens": 0,
                              "cacheCreationTokens": 0,
                              "cacheReadTokens": 0,
                              "totalTokens": 0
                            }
                          },
                          "apiFailure": null,
                          "quiescent": true,
                          "trace": {
                            "invocations": [],
                            "fileEffects": [],
                            "userTurns": ["sentinel-token-123"],
                            "assistantTurns": ["sentinel-token-123"],
                            "finalText": "trace sentinel-token-123",
                            "metrics": {
                              "controlIterations": 0,
                              "workerIterations": 0,
                              "totalIterations": 0,
                              "workerCycles": 0,
                              "toolCalls": 0,
                              "tokenUsage": {
                                "inputTokens": 0,
                                "outputTokens": 0,
                                "cacheCreationTokens": 0,
                                "cacheReadTokens": 0,
                                "totalTokens": 0
                              }
                            }
                          },
                          "diagnostics": ["sentinel-token-123"]
                        }
                        """, Map.of("MADA_EVAL_PROXY_PROVIDER_TOKEN_0", "sentinel-token-123")),
                "fake-eval-image:latest",
                List.of(),
                "none-test");
        try {
            EvalRunner runner = new EvalRunner(
                    null,
                    ScorerPipeline.of(new VerifyScriptScorer()),
                    executor,
                    AttemptArtifactWriter.NOOP);

            EvalCaseReport report = runner.runCase(loaded);

            EvalResult attempt = report.attempts().getFirst();
            assertFalse(attempt.terminalSummary().contains("sentinel-token-123"));
            assertFalse(attempt.detail().contains("sentinel-token-123"));
            assertTrue(attempt.detail().contains("[REDACTED_PROVIDER_TOKEN]"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private EvalCaseLoader.LoadedCase loadedCase(String id) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir.resolve("workspace"));
        Files.writeString(dir.resolve("workspace/answer.txt"), "OK\n");
        Files.writeString(dir.resolve("verify.sh"), """
                #!/usr/bin/env bash
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
                  "expectedVerdict": "PASS"
                }
                """.formatted(id));
        return new EvalCaseLoader(dir.getParent()).loadAll().stream()
                .filter(loaded -> loaded.evalCase().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private String fakeDocker(boolean available) throws IOException {
        Path script = tempDir.resolve("fake-docker-" + available + ".sh");
        Files.writeString(script, """
                #!/bin/sh
                set -eu
                case "${1:-}" in
                  info)
                    if [ "%s" = "true" ]; then
                      exit 0
                    fi
                    echo "docker unavailable"
                    exit 1
                    ;;
                  image)
                    echo "fake-eval-image@sha256:test"
                    exit 0
                    ;;
                  network)
                    if [ "${2:-}" = "create" ]; then
                      printf '%%s\\n' "$*" | tr ' ' '\\n' > "%s"
                    fi
                    exit 0
                    ;;
                  inspect)
                    echo "true"
                    exit 0
                    ;;
                  rm)
                    exit 0
                    ;;
                  run)
                    json=""
                    out=""
                    input=""
                    verify=""
                    proxy=""
                    args="$*"
                    while [ "$#" -gt 0 ]; do
                      case "$1" in
                        --name)
                          shift
                          case "$1" in
                            mada-eval-proxy-*)
                              proxy="true"
                              ;;
                          esac
                          ;;
                        -e)
                          shift
                          case "$1" in
                            MADA_EVAL_OUTCOME=*)
                              json="${1#MADA_EVAL_OUTCOME=}"
                              ;;
                          esac
                          ;;
                        -v)
                          shift
                          case "$1" in
                            *:/input:ro)
                              input="${1%%:/input:ro}"
                              ;;
                            *:/output:rw)
                              out="${1%%:/output:rw}"
                              ;;
                            *:/judge/verify.sh:ro)
                              verify="${1%%:/judge/verify.sh:ro}"
                              ;;
                          esac
                          ;;
                      esac
                      shift || true
                    done
                    if [ "$proxy" = "true" ]; then
                      printf '%%s\\n' "$args" | tr ' ' '\\n' > "%s"
                      while true; do sleep 1; done
                    fi
                    if [ -n "$out" ]; then
                      mkdir -p "$out"
                      if [ -n "$input" ] && [ -f "$input/attempt.json" ]; then
                        printf '%%s\\n' "$args" | tr ' ' '\\n' > "%s"
                        cp "$input/attempt.json" "%s"
                        printf '%%s' "$json" > "$out/outcome.json"
                      else
                        printf '%%s\\n' "$args" | tr ' ' '\\n' > "%s"
                        exit 0
                      fi
                    elif [ -n "$verify" ]; then
                      printf '%%s\\n' "$args" | tr ' ' '\\n' > "%s"
                      exit 0
                    fi
                    exit 0
                    ;;
                esac
                echo "unexpected docker args: $*" >&2
                exit 1
                """.formatted(
                        Boolean.toString(available),
                        tempDir.resolve("docker-network-args.txt"),
                        tempDir.resolve("docker-proxy-args.txt"),
                        tempDir.resolve("docker-run-args.txt"),
                        tempDir.resolve("captured-attempt.json"),
                        tempDir.resolve("docker-verify-args.txt"),
                        tempDir.resolve("docker-verify-args.txt")));
        script.toFile().setExecutable(true);
        return script.toString();
    }

    private String fakeDockerWithOutcome(String outcome, Map<String, String> inheritedEnv) throws IOException {
        Path outcomeFile = tempDir.resolve("prepared-outcome.json");
        Files.writeString(outcomeFile, outcome);
        Path script = tempDir.resolve("fake-docker-outcome.sh");
        Files.writeString(script, """
                #!/bin/sh
                set -eu
                case "${1:-}" in
                  info)
                    exit 0
                    ;;
                  image)
                    echo "fake-eval-image@sha256:test"
                    exit 0
                    ;;
                  network)
                    exit 0
                    ;;
                  inspect)
                    echo "true"
                    exit 0
                    ;;
                  rm)
                    exit 0
                    ;;
                  run)
                    out=""
                    proxy=""
                    while [ "$#" -gt 0 ]; do
                      case "$1" in
                        --name)
                          shift
                          case "$1" in
                            mada-eval-proxy-*)
                              proxy="true"
                              ;;
                          esac
                          ;;
                        -v)
                          shift
                          case "$1" in
                            *:/output:rw)
                              out="${1%%:/output:rw}"
                              ;;
                          esac
                          ;;
                      esac
                      shift || true
                    done
                    if [ "$proxy" = "true" ]; then
                      while true; do sleep 1; done
                    fi
                    if [ -n "$out" ]; then
                      mkdir -p "$out"
                      cp "%s" "$out/outcome.json"
                    fi
                    exit 0
                    ;;
                esac
                echo "unexpected docker args: $*" >&2
                exit 1
                """.formatted(outcomeFile));
        script.toFile().setExecutable(true);
        inheritedEnv.forEach(System::setProperty);
        return script.toString();
    }
}
