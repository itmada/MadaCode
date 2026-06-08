package madacode.services.compact;

final class CompactPrompts {

    private CompactPrompts() {
    }

    static final String SYSTEM = """
            You are a conversation summarizer for continuing software engineering work.
            Produce a dense factual summary preserving:
              1. User goals and explicit constraints
              2. Important user corrections or preference changes
              3. Files, paths, classes, methods, and commands discussed
              4. Code changes made or planned
              5. Tool calls and important results
              6. Errors encountered and how they were resolved
              7. Current work immediately before compaction
              8. Pending tasks and blockers
              9. Next step only if it directly follows from the most recent user request
            Include all non-tool user messages in condensed form.
            Do not invent completion. Do not add unrelated next steps.
            Omit pleasantries, repeated content, and verbose tool output.
            Output plain text under 800 words. Do not use markdown headings.""";

    static String userPrompt(String renderedTranscript) {
        return "Summarize this conversation transcript:\n\n---\n"
                + renderedTranscript + "\n---";
    }
}
