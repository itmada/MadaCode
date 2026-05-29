package madacode;

import madacode.cli.BufferedRepl;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.CancellationToken;
import madacode.core.ConversationSession;
import madacode.core.Message;
import madacode.core.QueryEngine;
import madacode.core.SessionStorage;
import madacode.core.TurnExecutor;
import madacode.core.TurnLog;
import madacode.core.QueryEngineTurnRunner;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReplTranscriptPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void replPersistsTranscriptAfterEachTurn() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(tempDir.resolve("workspace"));

        QueryEngine engine = new QueryEngine(
                new SingleResponseApiClient(),
                new ToolRegistry(),
                new SystemPromptBuilder(),
                PermissionGate.permissive());

        BufferedReader reader = new BufferedReader(new StringReader("hello\nexit\n"));
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(outputBuffer, true);
        TurnLog turnLog = new TurnLog(tempDir);
        TurnExecutor turnExecutor = new TurnExecutor(new QueryEngineTurnRunner(engine), turnLog);
        BufferedRepl repl = new BufferedRepl(engine, turnExecutor, session, reader, output, storage);

        repl.run();

        Path transcriptPath = storage.transcriptPath(session.sessionId());
        assertTrue(Files.isRegularFile(transcriptPath));

        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(3, restored.messages().size());
        assertEquals("hello", restored.messages().get(1).content());
        assertEquals("Hi from test!", restored.messages().get(2).content());
    }

    private static final class SingleResponseApiClient implements ApiClient {

        @Override
        public ApiResponse send(
                List<Message> messages,
                String systemPrompt,
                Collection<Tool<?>> tools,
                ApiStreamSink sink,
                CancellationToken cancellationToken) {
            sink.onTextDelta("Hi from test!");
            return new ApiResponse("Hi from test!", List.of());
        }
    }
}
