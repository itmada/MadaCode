package madacode.services.compact;

final class CompactPrompts {

    private CompactPrompts() {
    }

    static final String SYSTEM = """
            You are a conversation summarizer. Summarize the transcript of an AI
            coding assistant session. Produce a dense factual summary preserving:
              - User's stated goals and constraints
              - Files, paths, and function names mentioned or modified
              - Tool calls made and their key results
              - Decisions made and their rationale
              - Any pending tasks or unresolved questions
            Omit pleasantries, repeated content, and verbose tool output.
            Output plain text under 800 words. Do not use markdown headings.""";

    static String userPrompt(String renderedTranscript) {
        return "Summarize this conversation transcript:\n\n---\n"
                + renderedTranscript + "\n---";
    }
}
