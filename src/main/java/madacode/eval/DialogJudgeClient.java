package madacode.eval;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral boundary for LLM-based dialog judgment. Scorers depend on this small
 * contract; runtime/provider adapters remain replaceable and tests can use deterministic fakes.
 */
public interface DialogJudgeClient {

    Judgment judge(Request request);

    Descriptor descriptor();

    record Request(
            String caseId,
            String rubric,
            Boolean expectClarifyingQuestion,
            List<String> userTurns,
            List<String> assistantTurns,
            String finalText) {

        public Request {
            caseId = Objects.requireNonNull(caseId, "caseId");
            userTurns = userTurns == null ? List.of() : List.copyOf(userTurns);
            assistantTurns = assistantTurns == null ? List.of() : List.copyOf(assistantTurns);
            finalText = finalText == null ? "" : finalText;
        }
    }

    record Judgment(
            EvalResult.JudgeStatus status,
            String rationale) {

        public Judgment {
            Objects.requireNonNull(status, "status");
            if (status != EvalResult.JudgeStatus.PASS
                    && status != EvalResult.JudgeStatus.FAIL
                    && status != EvalResult.JudgeStatus.ERROR) {
                throw new IllegalArgumentException(
                        "dialog judgment status must be PASS, FAIL, or ERROR");
            }
            rationale = rationale == null ? "" : rationale;
        }
    }

    record Descriptor(
            String provider,
            String model,
            double temperature,
            Long seed,
            String schemaVersion) {

        public Descriptor {
            provider = normalized(provider, "provider");
            model = normalized(model, "model");
            schemaVersion = normalized(schemaVersion, "schemaVersion");
            if (!Double.isFinite(temperature) || temperature < 0.0) {
                throw new IllegalArgumentException("temperature must be finite and non-negative");
            }
        }

        public String fingerprint() {
            return provider + "/" + model
                    + ";temperature=" + temperature
                    + ";seed=" + (seed == null ? "(none)" : seed)
                    + ";schema=" + schemaVersion;
        }

        private static String normalized(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.strip();
        }
    }
}
