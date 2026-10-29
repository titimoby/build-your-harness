import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static dev.tamboui.toolkit.Toolkit.*;

// TamboUI version of Display (included with //SOURCES from step 5 on): same methods, so the agent loop does not change.
// Not part of the harness.
// Threads: the agent loop keeps the main thread; the TUI runs on its own thread (TamboUI's render thread).
// - the agent posts every UI change with runOnRenderThread (UI state is only touched by the render thread)
// - readUserInput() blocks on a queue that the input field fills on Enter
// - with inspect, a background thread tails inspect.log into the inspector panel
final class TuiDisplay {

    private static final Color CYAN = Color.rgb(0, 180, 216);
    private static final Color GREEN = Color.rgb(46, 204, 113);
    private static final Color YELLOW = Color.rgb(241, 196, 15);
    private static final Color RED = Color.rgb(231, 76, 60);
    private static final Color DIM = Color.rgb(127, 140, 141);
    private static final Color TEXT = null; // the terminal's own foreground: black on a light theme, white on a dark one
    private static final int MAX_WIDTH = 100;
    private static final int MAX_INSPECTOR_LINES = 5_000;

    private record Line(String content, Color color, boolean bold) {
        static Line blank() {
            return new Line("", TEXT, false);
        }

        StyledElement<?> toElement() {
            var style = color == null ? Style.EMPTY : Style.EMPTY.fg(color);
            if (bold) style = style.bold();
            return text(content).style(style).overflow(Overflow.WRAP_WORD);
        }
    }

    // UI state: only read and written on the render thread.
    private final List<Line> conversation = new ArrayList<>();
    private final List<Line> inspector = new ArrayList<>();
    // Kept across renders so they remember their scroll position. Sticky scroll follows new lines,
    // pauses when the user scrolls up (wheel, or arrows/PgUp once focused with Tab), resumes with End.
    private final ListElement<Line> conversationList = scrollingList("conversation", conversation);
    private final ListElement<Line> inspectorList = scrollingList("inspector", inspector);
    private final TextInputState input = new TextInputState();
    private String header = "";
    private boolean busy = false;
    private boolean showInspector;
    private boolean selectMode = false; // mouse handed back to the terminal for text selection
    private CompletableFuture<Boolean> pendingApproval = null; // set while waiting for y/n
    private int turnCalls = 0;
    private long turnInput = 0;
    private long turnOutput = 0;
    private double sessionCost = 0;
    private double contextPercent = 0;

    // Bridge between the agent thread and the UI thread. Optional.empty() means "the UI was closed".
    private final BlockingQueue<Optional<String>> submitted = new LinkedBlockingQueue<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Thread uiThread;
    private volatile ToolkitRunner runner;
    private volatile boolean interrupted = false; // Ctrl+C: stop everything once the terminal is restored

    TuiDisplay(boolean inspect) {
        this.showInspector = inspect;
        uiThread = Thread.ofPlatform().name("tui").start(this::runUi);
        await(started);
        if (inspect) Thread.ofVirtual().start(() -> tail(Path.of("inspect.log")));
        // An exception escaping the agent loop: close the TUI first, so the stack trace lands in a restored terminal.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            close();
            error.printStackTrace();
        });
    }

    // ---- Same methods as Display, called from the agent thread ----

    void banner(String model, String baseUrl) {
        onUi(() -> header = model + " @ " + baseUrl);
    }

    String readUserInput() {
        onUi(() -> {
            printTurnSummary();
            busy = false;
        });
        Optional<String> text = take();
        if (text.isEmpty()) return null; // the UI was closed (Ctrl+C)
        if (text.get().equals("exit")) close();
        return text.get();
    }

    void answer(String text) {
        onUi(() -> {
            conversation.add(Line.blank());
            text.lines().forEach(line -> conversation.add(new Line("  " + line, TEXT, false)));
        });
    }

    void toolCall(ToolExecutionRequest call) {
        String line = "  ● " + call.name() + "  " + mainArgument(call.arguments());
        onUi(() -> conversation.add(new Line(line, CYAN, false)));
    }

    void toolResult(String result) {
        Line line = summarize(result);
        onUi(() -> conversation.add(line));
    }

    void diff(String oldText, String newText) {
        onUi(() -> {
            oldText.lines().forEach(l -> conversation.add(new Line("    │ - " + l, RED, false)));
            newText.lines().forEach(l -> conversation.add(new Line("    │ + " + l, GREEN, false)));
        });
    }

    // Asks the user to approve a tool call (step 7). Blocks the agent thread until y / n.
    boolean confirm(ToolExecutionRequest call) {
        var approval = new CompletableFuture<Boolean>();
        List<Line> preview = approvalPreview(call.arguments());
        onUi(() -> {
            conversation.addAll(preview);
            conversation.add(new Line("    Allow? [y]es / [n]o", YELLOW, true));
            pendingApproval = approval;
        });
        try {
            return approval.get();
        } catch (Exception e) {
            return false;
        }
    }

    void usage(int inputTokens, int outputTokens, double sessionCost, double contextPercent) {
        onUi(() -> {
            turnCalls++;
            turnInput += inputTokens;
            turnOutput += outputTokens;
            this.sessionCost = sessionCost;
            this.contextPercent = contextPercent;
        });
    }

    // ---- UI thread ----

    private void runUi() {
        try (var r = ToolkitRunner.builder().tickRate(Duration.ofMillis(50)).mouseCapture(true).build()) {
            runner = r;
            started.countDown();
            r.run(this::render);
        } catch (Exception e) {
            started.countDown();
        } finally {
            submitted.add(Optional.empty()); // unblock readUserInput() whatever happens
        }
        // The runner is closed here, so the terminal is restored: Ctrl+C can stop the program, even mid-model-call.
        if (interrupted) System.exit(130);
    }

    private Element render() {
        var headerRow = row(
                text(" " + header).fg(DIM),
                spacer(),
                text(pendingApproval != null ? "[approval needed] " : busy ? "[working…] " : "[ready] ")
                        .fg(busy ? YELLOW : GREEN)
        ).length(1);

        var conversationPanel = panel("Conversation", conversationList).rounded().borderColor(DIM).fill(2);
        var inspectorPanel = panel("Inspector · inspect.log", inspectorList).rounded().borderColor(DIM).fill(1);

        var body = showInspector ? row(conversationPanel, inspectorPanel) : row(conversationPanel);

        var statusRow = row(
                text(selectMode ? " SELECT: mouse selects text, Ctrl+T to scroll again " : "").fg(YELLOW).bold(),
                text(" session $%.4f · context %.1f%%   ·   wheel/Tab+↑↓ scroll · End follow · Ctrl+T select text · Ctrl+O inspector · Ctrl+C quit"
                        .formatted(sessionCost, contextPercent)).fg(DIM)
        ).length(1);

        var inputRow = row(
                text(busy ? " … " : " > ").fg(busy ? YELLOW : GREEN).bold(),
                textInput(input)
                        .placeholder(busy ? "" : "Ask anything…")
                        .showCursor(!busy)
                        .cursorRequiresFocus(false)
                        .onSubmit(this::submit)
                        .fill()
        ).length(1);

        return column(headerRow, body.fill(), statusRow, inputRow)
                .id("main").focusable().onKeyEvent(this::handleKey);
    }

    private EventResult handleKey(KeyEvent event) {
        if (event.isChar('c') && event.hasCtrl()) { // checked first: must work even while the agent is busy
            interrupted = true;
            runner.quit();
            return EventResult.HANDLED;
        }
        if (event.isChar('t') && event.hasCtrl()) {
            toggleSelectMode();
            return EventResult.HANDLED;
        }
        if (event.isChar('o') && event.hasCtrl()) {
            showInspector = !showInspector;
            return EventResult.HANDLED;
        }
        if (pendingApproval != null) {
            if (event.isCharIgnoreCase('y') || event.isCharIgnoreCase('n') || event.matches(Actions.CANCEL)) {
                boolean approved = event.isCharIgnoreCase('y');
                conversation.add(approved ? new Line("    ✓ approved", GREEN, false) : new Line("    ✗ denied", RED, false));
                pendingApproval.complete(approved);
                pendingApproval = null;
            }
            return EventResult.HANDLED;
        }
        if (busy) return EventResult.HANDLED; // no typing while the agent works
        if (event.matches(Actions.CONFIRM)) {
            submit();
            return EventResult.HANDLED;
        }
        if (handleTextInputKey(input, event)) return EventResult.HANDLED;
        return EventResult.UNHANDLED;
    }

    // Mouse capture gives us the wheel but takes the mouse away from the terminal: no text selection.
    // Select mode hands the mouse back to the terminal until Ctrl+T is pressed again.
    private void toggleSelectMode() {
        try {
            var backend = runner.tuiRunner().backend();
            if (selectMode) backend.enableMouseCapture();
            else backend.disableMouseCapture();
            selectMode = !selectMode;
        } catch (IOException e) {
            // the terminal refused: stay in the current mode
        }
    }

    private void submit() {
        String text = input.text().trim();
        if (text.isEmpty() || busy) return;
        input.clear();
        conversation.add(Line.blank());
        conversation.add(new Line("> " + text, GREEN, true));
        busy = true;
        submitted.add(Optional.of(text));
    }

    private void printTurnSummary() {
        if (turnCalls == 0) return;
        conversation.add(Line.blank());
        conversation.add(new Line("  %d call%s · %,d in / %,d out · session $%.4f · context %.1f%%".formatted(
                turnCalls, turnCalls > 1 ? "s" : "", turnInput, turnOutput, sessionCost, contextPercent), DIM, false));
        turnCalls = 0;
        turnInput = 0;
        turnOutput = 0;
    }

    // ---- Helpers ----

    private static ListElement<Line> scrollingList(String id, List<Line> lines) {
        return list().data(lines, Line::toElement)
                .displayOnly().stickyScroll().scrollbar().scrollbarThumbColor(DIM)
                .id(id).focusable();
    }

    private void onUi(Runnable action) {
        runner.runOnRenderThread(action);
    }

    private void close() {
        onUi(runner::quit);
        try {
            uiThread.join(); // the terminal is restored before the agent goes on
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Same summary rules as the console Display.
    private static Line summarize(String result) {
        if (result.startsWith("Error")) return new Line("    └ " + shorten(result), RED, false);
        List<String> lines = new ArrayList<>(result.strip().lines().toList());
        String status = "";
        if (!lines.isEmpty() && lines.getLast().matches("\\[(exit code|killed).*]")) {
            String last = lines.removeLast();
            status = " · " + last.substring(1, last.length() - 1);
        }
        while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
        String summary = switch (lines.size()) {
            case 0 -> "(no output)";
            case 1 -> shorten(lines.getFirst());
            default -> lines.size() + " lines";
        };
        Color color = status.isEmpty() || status.equals(" · exit code 0") ? TEXT : RED;
        return new Line("    └ " + summary + status, color, false);
    }

    // What the user approves: the full command, the diff of an edit, the size of a write.
    private static List<Line> approvalPreview(String arguments) {
        List<Line> lines = new ArrayList<>();
        try {
            JsonNode node = new ObjectMapper().readTree(arguments);
            if (node.has("old_text") && node.has("new_text")) {
                node.get("old_text").asText().lines().forEach(l -> lines.add(new Line("    │ - " + l, RED, false)));
                node.get("new_text").asText().lines().forEach(l -> lines.add(new Line("    │ + " + l, GREEN, false)));
            } else if (node.has("content")) {
                lines.add(new Line("    │ " + node.get("content").asText().lines().count() + " lines to write", TEXT, false));
            } else if (node.has("command")) {
                node.get("command").asText().lines().forEach(l -> lines.add(new Line("    │ $ " + l, TEXT, false)));
            }
        } catch (Exception e) {
            lines.add(new Line("    │ " + arguments, TEXT, false));
        }
        return lines;
    }

    // Shows the argument a human cares about (command, path...) instead of the raw JSON.
    private static String mainArgument(String arguments) {
        try {
            JsonNode node = new ObjectMapper().readTree(arguments);
            if (node.isEmpty()) return "";
            JsonNode main = node.has("path") ? node.get("path") : node.elements().next();
            return shorten(main.isTextual() ? main.asText() : main.toString());
        } catch (Exception e) {
            return shorten(arguments);
        }
    }

    private static String shorten(String text) {
        List<String> lines = text.strip().lines().toList();
        String first = lines.isEmpty() ? "" : lines.getFirst();
        boolean cut = first.length() > MAX_WIDTH || lines.size() > 1;
        return (first.length() > MAX_WIDTH ? first.substring(0, MAX_WIDTH) : first) + (cut ? " …" : "");
    }

    // Follows inspect.log like `tail -F`: new lines go to the inspector panel.
    private void tail(Path file) {
        long position = 0;
        while (uiThread.isAlive()) {
            try {
                if (Files.exists(file)) {
                    long size = Files.size(file);
                    if (size < position) position = 0; // file was truncated: a new run started
                    if (size > position) {
                        byte[] bytes = new byte[(int) (size - position)];
                        try (var reader = new RandomAccessFile(file.toFile(), "r")) {
                            reader.seek(position);
                            reader.readFully(bytes);
                        }
                        position = size;
                        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
                        onUi(() -> {
                            lines.forEach(l -> inspector.add(new Line(l, TEXT, false)));
                            while (inspector.size() > MAX_INSPECTOR_LINES) inspector.removeFirst();
                        });
                    }
                }
                Thread.sleep(200);
            } catch (Exception e) {
                return;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<String> take() {
        try {
            return submitted.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
