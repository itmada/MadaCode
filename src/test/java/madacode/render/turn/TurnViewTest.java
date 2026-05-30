package madacode.render.turn;

import madacode.tui.Screen;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.render.tool.ToolDisplayRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

import madacode.tui.TerminalText;

class TurnViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal Screen that records setLiveStatus calls. */
    private static class RecScreen implements Screen {
        final List<List<String>> statusCalls = new CopyOnWriteArrayList<>();
        final List<List<String>> scrollbackCalls = new ArrayList<>();
        List<String> lastScrollback = List.of();

        @Override public void scrollback(List<String> lines) {
            scrollbackCalls.add(List.copyOf(lines));
            lastScrollback = lines;
        }
        @Override public void setLiveStatus(List<String> lines) { statusCalls.add(List.copyOf(lines)); }
        @Override public void clearLiveStatus() { statusCalls.add(List.of()); }
        @Override public void commitScrollbackAndSetStatus(List<String> sc, List<String> live) {
            scrollback(sc);
            setLiveStatus(live);
        }
        @Override public int width() { return 80; }
        @Override public int height() { return 30; }
        @Override public void flush() {}
    }

    @Test
    void shouldPaintOnMarkDirty() throws InterruptedException {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("Hello");           // no \n — stays as live partial
        tv.add(text);

        // Wait for coalesced paint
        Thread.sleep(50);
        assertFalse(screen.statusCalls.isEmpty(), "should have called setLiveStatus");
        List<String> last = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(last.stream().anyMatch(l -> l.contains("Hello")),
                "partial should be in live status");
        tv.shutdown();
    }

    @Test
    void shouldCoalesceMultipleMarkDirty() throws InterruptedException {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        AssistantTextRenderable text = new AssistantTextRenderable();
        tv.add(text);
        for (int i = 0; i < 10; i++) {
            text.append("chunk" + i);
            tv.markDirty();
        }

        Thread.sleep(50);
        // All 10 markDirty calls should result in at most a few paints
        assertTrue(screen.statusCalls.size() <= 5, "should coalesce: " + screen.statusCalls.size());
        tv.shutdown();
    }

    @Test
    void shouldSpillFinalizedPrefixOnPaint() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Add finalized tool cards
        for (int i = 0; i < 20; i++) {
            ToolCardRenderable card = new ToolCardRenderable("id" + i, "bash",
                    MAPPER.createObjectNode(), ToolDisplayRegistry.defaults());
            card.finalizeTool(true, 100);
            tv.add(card);
        }

        // Trigger synchronous paint
        tv.flushNow();

        // All finalized cards should have been spilled to scrollback
        assertFalse(screen.scrollbackCalls.isEmpty(), "should have spilled finalized items");
        assertTrue(tv.items().isEmpty(), "all finalized items should be removed from live");
        tv.shutdown();
    }

    @Test
    void shouldNotSpillUnfinalizedPrefix() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // First item is NOT finalized
        AssistantTextRenderable running = new AssistantTextRenderable();
        running.append("still going...");
        tv.add(running);

        // Many finalized items after it
        for (int i = 0; i < 30; i++) {
            AssistantTextRenderable t = new AssistantTextRenderable();
            t.append("done " + i + "\n");
            t.finalizeText();
            tv.add(t);
        }

        tv.flushNow();

        // Should NOT spill anything because first item is not finalized
        assertTrue(screen.scrollbackCalls.isEmpty(), "nothing should spill past unfinalized item");
        assertEquals(31, tv.items().size(), "all items still in live area");
        tv.shutdown();
    }

    @Test
    void shouldSpillAllOnEndTurn() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        AssistantTextRenderable t1 = new AssistantTextRenderable();
        t1.append("done");
        t1.finalizeText();
        tv.add(t1);

        tv.endTurn();
        assertFalse(screen.scrollbackCalls.isEmpty());
        assertTrue(screen.statusCalls.stream().anyMatch(List::isEmpty)); // clearLiveStatus called
    }

    @Test
    void shouldFindByToolUseId() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        ToolCardRenderable card = new ToolCardRenderable("tid-1", "bash",
                MAPPER.createObjectNode(), ToolDisplayRegistry.defaults());
        tv.add(card);

        ToolCardRenderable found = tv.findByToolUseId("tid-1", ToolCardRenderable.class);
        assertNotNull(found);
        assertEquals("tid-1", found.toolUseId());

        assertNull(tv.findByToolUseId("nonexistent", ToolCardRenderable.class));
        tv.shutdown();
    }

    @Test
    void scrollbackOrderMatchesItemsOrder() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // iter1: text1 → tool_search → tool_list → tool_read
        AssistantTextRenderable text1 = new AssistantTextRenderable();
        text1.append("Now let me read both files:\n");
        text1.finalizeText();
        tv.add(text1);

        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable cardSearch = new ToolCardRenderable("s1", "search", input, ToolDisplayRegistry.defaults());
        cardSearch.finalizeTool(true, 50);
        tv.add(cardSearch);

        ToolCardRenderable cardList = new ToolCardRenderable("s2", "list", input, ToolDisplayRegistry.defaults());
        cardList.finalizeTool(true, 30);
        tv.add(cardList);

        ToolCardRenderable cardRead = new ToolCardRenderable("s3", "read", input, ToolDisplayRegistry.defaults());
        cardRead.finalizeTool(true, 100);
        tv.add(cardRead);

        // iter2: text2
        AssistantTextRenderable text2 = new AssistantTextRenderable();
        text2.append("Here's a summary of the findings:\n");
        text2.finalizeText();
        tv.add(text2);

        tv.endTurn();

        // scrollback should have one batch with items in order
        List<String> all = screen.lastScrollback;
        assertFalse(all.isEmpty(), "should have scrollback content");

        int text1Idx = indexOfContaining(all, "Now let me read");
        int cardSearchIdx = indexOfContaining(all, "Search");
        int cardListIdx = indexOfContaining(all, "List");
        int cardReadIdx = indexOfContaining(all, "Read");
        int text2Idx = indexOfContaining(all, "summary of the findings");

        assertTrue(text1Idx < cardSearchIdx,
                "text1 (" + text1Idx + ") should be before card_search (" + cardSearchIdx + ")");
        assertTrue(cardSearchIdx < cardListIdx,
                "card_search (" + cardSearchIdx + ") should be before card_list (" + cardListIdx + ")");
        assertTrue(cardListIdx < cardReadIdx,
                "card_list (" + cardListIdx + ") should be before card_read (" + cardReadIdx + ")");
        assertTrue(cardReadIdx < text2Idx,
                "card_read (" + cardReadIdx + ") should be before text2 (" + text2Idx + ")");

        tv.shutdown();
    }

    @Test
    void prefixSpillPreservesInterleavedOrder() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // text1 finalized
        AssistantTextRenderable text1 = new AssistantTextRenderable();
        text1.append("line1\n");
        text1.finalizeText();
        tv.add(text1);

        // card1 finalized
        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card1 = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        card1.finalizeTool(true, 100);
        tv.add(card1);

        // text2 NOT finalized (streaming)
        AssistantTextRenderable text2 = new AssistantTextRenderable();
        text2.append("streaming...");
        tv.add(text2);

        // card2 finalized (after unfinalized text2)
        ToolCardRenderable card2 = new ToolCardRenderable("c2", "bash", input, ToolDisplayRegistry.defaults());
        card2.finalizeTool(true, 50);
        tv.add(card2);

        tv.flushNow();

        // Only text1 + card1 should be spilled (finalized prefix)
        assertFalse(screen.scrollbackCalls.isEmpty(), "should have spilled finalized prefix");
        List<String> spilled = screen.scrollbackCalls.get(0);
        int spilledText1Idx = indexOfContaining(spilled, "line1");
        int spilledCard1Idx = indexOfContaining(spilled, "Bash");

        assertTrue(spilledText1Idx < spilledCard1Idx,
                "spilled text1 before card1");
        assertTrue(spilled.stream().noneMatch(l -> l.contains("streaming")),
                "text2 not spilled");

        // Live area should have text2 + card2
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        int liveText2Idx = indexOfContaining(live, "streaming");
        int liveCard2Idx = indexOfContaining(live, "Bash");
        assertTrue(liveText2Idx < liveCard2Idx,
                "live text2 before card2");

        tv.shutdown();
    }

    @Test
    void pureText_drainsCommittedLinesIncrementally() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        AssistantTextRenderable text = new AssistantTextRenderable();
        tv.add(text);

        // First chunk with newline — committed line should drain
        text.append("line one\n");
        tv.flushNow();

        List<String> firstScrollback = screen.scrollbackCalls.isEmpty()
                ? List.of() : screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(firstScrollback.stream().anyMatch(l -> l.contains("line one")),
                "first committed line should drain to scrollback");

        // Second chunk — another committed line
        text.append("line two\n");
        tv.flushNow();

        List<String> secondScrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(secondScrollback.stream().anyMatch(l -> l.contains("line two")),
                "second committed line should drain incrementally");

        // text is still unfinalized, still in items
        assertFalse(text.isFinalized());
        assertEquals(1, tv.items().size(), "text still in live area");

        // Partial stays in live area
        text.append("partial");
        tv.flushNow();
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(live.stream().anyMatch(l -> l.contains("partial")),
                "partial in live area");
        assertTrue(live.stream().anyMatch(l -> l.contains("▌")),
                "cursor in live area");

        tv.shutdown();
    }

    @Test
    void pureText_endTurn_noDuplicationNoLoss() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        AssistantTextRenderable text = new AssistantTextRenderable();
        tv.add(text);

        text.append("line one\nline two\npartial end");
        tv.flushNow();
        // committed lines drained to scrollback

        text.finalizeText();
        tv.endTurn();

        // Collect all scrollback content
        List<String> all = new ArrayList<>();
        for (List<String> batch : screen.scrollbackCalls) {
            all.addAll(batch);
        }

        // commonmark parses "line one\nline two\n" as single paragraph
        String joined = String.join(" ", all.stream().map(TurnViewTest::stripAnsi).toList());
        assertTrue(joined.contains("line one"), "line one present: " + joined);
        assertTrue(joined.contains("line two"), "line two present: " + joined);
        assertTrue(joined.contains("partial end"), "partial end present: " + joined);

        tv.shutdown();
    }

    @Test
    void unfinalizedCardBlocksTextDrain() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Running card (unfinalized) blocks everything after it
        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        tv.add(card);

        // Text after running card — committed lines can't drain past the card
        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("blocked line\npartial");
        tv.add(text);

        tv.flushNow();

        // Nothing should be spilled — card blocks drain
        assertTrue(screen.scrollbackCalls.isEmpty(), "unfinalized card blocks text drain");

        // Live area has card + text's partial (committed lines stay in committedLines)
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(live.stream().anyMatch(l -> l.contains("partial")),
                "text partial visible in live area");
        assertTrue(live.stream().anyMatch(l -> l.contains("Bash")),
                "card visible in live area");

        tv.shutdown();
    }

    @Test
    void secondTurnStillStreamsAfterFirstEndTurn() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // ---- Turn 1 ----
        AssistantTextRenderable text1 = new AssistantTextRenderable();
        tv.add(text1);
        text1.append("turn1 line1\n");
        tv.flushNow();
        text1.append("turn1 line2\n");
        tv.flushNow();
        text1.finalizeText();
        tv.endTurn();

        int turn1ScrollbackCount = screen.scrollbackCalls.size();
        assertTrue(turn1ScrollbackCount >= 2, "turn 1 should have incremental scrollback");

        // ---- Turn 2: markDirty must NOT be blocked by ended flag ----
        AssistantTextRenderable text2 = new AssistantTextRenderable();
        tv.add(text2);
        text2.append("turn2 line1\n");
        tv.flushNow();

        // turn 2's flushNow must produce scrollback (not silently dropped)
        List<String> lastScrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(lastScrollback.stream().anyMatch(l -> l.contains("turn2 line1")),
                "turn 2 should still drain committed lines to scrollback");

        text2.append("turn2 line2\n");
        tv.flushNow();

        List<String> secondScrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(secondScrollback.stream().anyMatch(l -> l.contains("turn2 line2")),
                "turn 2 second line should drain incrementally");

        text2.finalizeText();
        tv.endTurn();

        // Verify total scrollback has both turns' content
        List<String> all = new ArrayList<>();
        for (List<String> batch : screen.scrollbackCalls) {
            all.addAll(batch);
        }
        assertTrue(all.stream().anyMatch(l -> l.contains("turn1 line1")));
        assertTrue(all.stream().anyMatch(l -> l.contains("turn1 line2")));
        assertTrue(all.stream().anyMatch(l -> l.contains("turn2 line1")));
        assertTrue(all.stream().anyMatch(l -> l.contains("turn2 line2")));

        tv.shutdown();
    }

    @Test
    void shutdownBlocksFurtherPaints() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        tv.shutdown();

        // After shutdown, markDirty should be a no-op
        AssistantTextRenderable text = new AssistantTextRenderable();
        tv.add(text);
        text.append("should not appear\n");

        // flushNow is also guarded by ended
        tv.flushNow();

        assertTrue(screen.scrollbackCalls.isEmpty(),
                "no scrollback after shutdown");
    }

    @Test
    void liveLinesHaveLeadingMarginForUnspilledItem() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Unfinalized tool card
        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        tv.add(card);

        tv.flushNow();

        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertFalse(live.isEmpty(), "should have live content");
        assertTrue(live.get(0).isEmpty(),
                "first live line should be margin blank for unspilled item");
        assertTrue(live.stream().anyMatch(l -> l.contains("Bash")),
                "card should be in live area");

        tv.shutdown();
    }

    @Test
    void cardLineDoesNotShiftWhenSpillOccurs() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        tv.add(card);

        // Pass 1: unfinalized card in live with leading margin
        tv.flushNow();
        List<String> live1 = screen.statusCalls.get(screen.statusCalls.size() - 1);
        int cardStartIdx1 = indexOfContaining(live1, "Bash");
        assertTrue(cardStartIdx1 > 0, "card should have leading margin in live");

        // Pass 2: finalize card — should spill to scrollback
        card.finalizeTool(true, 100);
        tv.flushNow();

        List<String> scrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        int scrollCardIdx = indexOfContaining(scrollback, "Bash");
        // The card's content line appears at the same relative position
        // (margin at index 0, card content at index 1)
        assertEquals(1, scrollCardIdx,
                "card content should be at index 1 after margin in scrollback");

        tv.shutdown();
    }

    @Test
    void textThenToolUse_textFlushed_cardInLive() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Finalized text
        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("done text\n");
        text.finalizeText();
        tv.add(text);

        // Running card
        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        tv.add(card);

        tv.flushNow();

        // Text should be in scrollback
        List<String> scrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(scrollback.stream().anyMatch(l -> l.contains("done text")),
                "finalized text in scrollback");

        // Card should be in live
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(live.stream().anyMatch(l -> l.contains("Bash")),
                "running card in live");

        tv.shutdown();
    }

    @Test
    void cardRunningThenText_textCommittedNotDrained() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Running card blocks prefix
        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        tv.add(card);

        // Streaming text with committed line after the card
        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("blocked line\npartial");
        tv.add(text);

        tv.flushNow();

        // Nothing in scrollback — card blocks the prefix
        assertTrue(screen.scrollbackCalls.isEmpty(),
                "running card blocks all scrollback drain");

        // Both card and text partial in live
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(live.stream().anyMatch(l -> l.contains("Bash")),
                "card in live");
        assertTrue(live.stream().anyMatch(l -> l.contains("partial")),
                "text partial in live");

        tv.shutdown();
    }

    @Test
    void cardFinalizedThenStreamingText_cardSpilled_textPartialStays() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Finalized card
        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        card.finalizeTool(true, 100);
        tv.add(card);

        // Streaming text
        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("streaming text\npartial");
        tv.add(text);

        tv.flushNow();

        // Card should be in scrollback
        List<String> scrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(scrollback.stream().anyMatch(l -> l.contains("Bash")),
                "finalized card in scrollback");

        // Text committed line should also be in scrollback (after card in prefix)
        assertTrue(scrollback.stream().anyMatch(l -> l.contains("streaming text")),
                "text committed line drained after finalized card");

        // Text partial stays in live
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(live.stream().anyMatch(l -> l.contains("partial")),
                "text partial in live");

        tv.shutdown();
    }

    @Test
    void marginIssuedOnlyOnceAcrossManyPaints() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Streaming text that produces many committed lines over multiple paints
        AssistantTextRenderable text = new AssistantTextRenderable();
        tv.add(text);

        for (int i = 0; i < 10; i++) {
            text.append("line " + i + "\n");
            tv.flushNow();
        }

        // Collect all scrollback lines
        List<String> allScrollback = new ArrayList<>();
        for (List<String> batch : screen.scrollbackCalls) {
            allScrollback.addAll(batch);
        }

        // Count leading margin blanks for this text item — should be exactly 1
        int marginCount = 0;
        boolean foundContent = false;
        for (String line : allScrollback) {
            if (line.contains("line ")) {
                foundContent = true;
            } else if (foundContent && line.isEmpty()) {
                // A blank after content — not our leading margin
            } else if (!foundContent && line.isEmpty()) {
                marginCount++;
            }
        }

        assertTrue(foundContent, "should have scrollback content");
        assertEquals(1, marginCount,
                "should have exactly one leading margin for the text item");

        tv.shutdown();
    }

    @Test
    void thinkingDismissedDoesNotLeaveMarginInScrollback() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        // Add thinking
        TurnStatusRenderable thinking = new TurnStatusRenderable("Thinking...", () -> {});
        tv.add(thinking);
        tv.flushNow();

        // Thinking shows in live, not scrollback
        assertTrue(screen.scrollbackCalls.isEmpty(), "thinking should not go to scrollback");

        // Dismiss thinking (finalize + remove)
        thinking.finalizeStatus();
        tv.remove(thinking);

        // Add text
        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("after thinking\n");
        tv.add(text);
        tv.flushNow();

        // Scrollback should have text content, no orphan margin from thinking
        List<String> scrollback = screen.scrollbackCalls.get(screen.scrollbackCalls.size() - 1);
        assertTrue(scrollback.stream().anyMatch(l -> l.contains("after thinking")),
                "text should be in scrollback");

        // The text's leading margin is fine, but no extra blank before it from thinking
        int firstNonBlank = -1;
        for (int i = 0; i < scrollback.size(); i++) {
            if (!scrollback.get(i).isEmpty()) {
                firstNonBlank = i;
                break;
            }
        }
        // Should have at most 1 leading blank (the text's own margin)
        long leadingBlanks = scrollback.stream()
                .takeWhile(String::isEmpty)
                .count();
        assertTrue(leadingBlanks <= 1,
                "at most one leading margin from text, no orphan from thinking");

        tv.shutdown();
    }

    @Test
    void permissionPhaseCardStaysInLive() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable card = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        card.enterPermissionPhase();
        tv.add(card);

        tv.flushNow();

        // Card should NOT be in scrollback — it's unfinalized
        assertTrue(screen.scrollbackCalls.isEmpty(),
                "permission-waiting card should not spill to scrollback");

        // Card should be in live with permission prompt
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        assertTrue(live.stream().anyMatch(l -> l.contains("Permission required")),
                "permission prompt in live area");

        tv.shutdown();
    }

    @Test
    void finalizedCardAfterPermissionWaiterStaysLiveUntilPrefixResolves() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        ObjectNode input = MAPPER.createObjectNode();
        ToolCardRenderable waiting = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        waiting.enterPermissionPhase();
        tv.add(waiting);

        ToolCardRenderable completedAfter = new ToolCardRenderable("c2", "file_read", input, ToolDisplayRegistry.defaults());
        completedAfter.setResultOutput(true, "hello");
        completedAfter.finalizeTool(true, 5);
        tv.add(completedAfter);

        tv.flushNow();

        assertTrue(screen.scrollbackCalls.isEmpty(),
                "completed later card must not pass permission waiter into scrollback");
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        String renderedLive = String.join("\n", live);
        assertTrue(renderedLive.contains("Permission required"), renderedLive);
        assertTrue(renderedLive.contains("Read()"), renderedLive);

        waiting.resolvePermission();
        waiting.setResultOutput(true, "ok");
        waiting.finalizeTool(true, 10);
        tv.flushNow();

        assertFalse(screen.scrollbackCalls.isEmpty(),
                "ordered prefix should spill once permission waiter finalizes");
        String renderedScrollback = String.join("\n", screen.lastScrollback);
        assertTrue(renderedScrollback.contains("Bash"), renderedScrollback);
        assertTrue(renderedScrollback.contains("Read()"), renderedScrollback);

        tv.shutdown();
    }

    @Test
    void pureQueuedCardsAfterPermissionWaiterAreHiddenFromLive() {
        RecScreen screen = new RecScreen();
        TurnView tv = new TurnView(screen);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "find .");
        ToolCardRenderable waiting = new ToolCardRenderable("c1", "bash", input, ToolDisplayRegistry.defaults());
        waiting.enterPermissionPhase();
        tv.add(waiting);

        ObjectNode queuedInput = MAPPER.createObjectNode();
        queuedInput.put("command", "ls -la");
        ToolCardRenderable queuedAfter = new ToolCardRenderable("c2", "bash", queuedInput, ToolDisplayRegistry.defaults());
        tv.add(queuedAfter);

        tv.flushNow();

        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        String renderedLive = String.join("\n", live);
        assertTrue(renderedLive.contains("Permission required"), renderedLive);
        assertTrue(renderedLive.contains("find ."), renderedLive);
        assertFalse(renderedLive.contains("ls -la"), renderedLive);

        queuedAfter.markStarted();
        tv.flushNow();

        live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        renderedLive = String.join("\n", live);
        assertTrue(renderedLive.contains("ls -la"), renderedLive);

        tv.shutdown();
    }

    /** Screen whose width can be changed to simulate terminal resize. */
    private static class ResizeScreen implements Screen {
        volatile int width;
        final List<List<String>> statusCalls = new CopyOnWriteArrayList<>();

        ResizeScreen(int initialWidth) { this.width = initialWidth; }

        @Override public void scrollback(List<String> lines) {}
        @Override public void setLiveStatus(List<String> lines) { statusCalls.add(List.copyOf(lines)); }
        @Override public void clearLiveStatus() { statusCalls.add(List.of()); }
        @Override public int width() { return width; }
        @Override public int height() { return 30; }
        @Override public void flush() {}
    }

    @Test
    void resizeNarrowerThenLayoutFitsNewWidth() {
        ResizeScreen screen = new ResizeScreen(80);
        TurnView tv = new TurnView(screen);

        // Add streaming text that produces long committed lines
        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("this is a very long line that should not fit in 20 columns when the terminal shrinks\n");
        text.append("streaming partial");
        tv.add(text);
        tv.flushNow();

        // Now simulate terminal resize to 20 columns
        screen.width = 20;
        tv.markDirty();

        // Wait for coalesced paint
        try { Thread.sleep(50); } catch (InterruptedException e) { /* ok */ }

        // Live status lines should not exceed new width
        List<String> live = screen.statusCalls.get(screen.statusCalls.size() - 1);
        for (String line : live) {
            int displayWidth = TerminalText.displayWidth(line);
            assertTrue(displayWidth <= 20,
                    "after resize to 20, line display width " + displayWidth
                    + " exceeds 20: \"" + line + "\"");
        }

        tv.shutdown();
    }

    @Test
    void committedTableUsesCurrentScreenWidth() {
        RecScreen screen = new RecScreen() {
            @Override public int width() { return 40; }
        };
        TurnView tv = new TurnView(screen);

        AssistantTextRenderable text = new AssistantTextRenderable();
        text.append("| year | events |\n");
        text.append("|------|--------|\n");
        text.append("| 2021 | AlphaFold GPT-3 multimodal agents regulation |\n");
        text.finalizeText();
        tv.add(text);

        tv.flushNow();

        List<String> all = new ArrayList<>();
        for (List<String> batch : screen.scrollbackCalls) {
            all.addAll(batch);
        }
        assertTrue(all.stream().anyMatch(l -> l.contains("AlphaFold")),
                "table content should spill: " + all);
        assertTrue(all.size() >= 3, "long cell should wrap into multiple table lines: " + all);
        for (String line : all) {
            int width = TerminalText.displayWidth(stripAnsi(line));
            assertTrue(width <= 40, "table line too wide (" + width + "): " + line);
        }

        tv.shutdown();
    }

    private static int indexOfContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i;
        }
        return -1;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "");
    }
}
