import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.List;

// Console rendering shared by steps 1 to 4 (included with //SOURCES).
// Not part of the harness, and replaceable (e.g. by the TamboUI version, TuiDisplay) without touching the steps.
final class Display {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final int MAX_WIDTH = 100;

    private final ObjectMapper json = new ObjectMapper();
    private final boolean inspect;
    private boolean startOfTurn = false;

    // Token usage is accumulated per call and printed once per user turn.
    private int turnCalls = 0;
    private long turnInput = 0;
    private long turnOutput = 0;
    private double sessionCost = 0;
    private double contextPercent = 0;

    // With --inspect, the raw JSON goes to inspect.log (see simplelogger.properties), not to the console.
    Display(boolean inspect) {
        this.inspect = inspect;
    }

    void banner(String model, String baseUrl) {
        IO.println(BOLD + model + RESET + DIM + " @ " + baseUrl + "  (type 'exit' to quit)" + RESET);
        if (inspect) IO.println(DIM + "Inspector: run `tail -F inspect.log` in another terminal" + RESET);
    }

    String readUserInput() {
        printTurnSummary();
        startOfTurn = true;
        return IO.readln("\n" + BOLD + "you> " + RESET);
    }

    void answer(String text) {
        IO.println("\n" + BOLD + "assistant> " + RESET + text);
    }

    void toolCall(ToolExecutionRequest call) {
        if (startOfTurn) IO.println();
        startOfTurn = false;
        IO.println("  " + CYAN + "● " + call.name() + RESET + "  " + mainArgument(call.arguments()));
    }

    void toolResult(String result) {
        if (result.startsWith("Error")) {
            IO.println("    " + RED + "└ " + shorten(result) + RESET);
            return;
        }
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
        String color = status.isEmpty() || status.equals(" · exit code 0") ? "" : RED; // "" = terminal default color
        IO.println("    " + color + "└ " + summary + status + RESET);
    }

    void diff(String oldText, String newText) {
        oldText.lines().forEach(line -> IO.println("    " + RED + "│ - " + line + RESET));
        newText.lines().forEach(line -> IO.println("    " + GREEN + "│ + " + line + RESET));
    }

    void usage(int input, int output, double sessionCost, double contextPercent) {
        turnCalls++;
        turnInput += input;
        turnOutput += output;
        this.sessionCost = sessionCost;
        this.contextPercent = contextPercent;
    }

    private void printTurnSummary() {
        if (turnCalls == 0) return;
        IO.println("\n  " + DIM + "%d call%s · %,d in / %,d out · session $%.4f · context %.1f%%".formatted(
                turnCalls, turnCalls > 1 ? "s" : "", turnInput, turnOutput, sessionCost, contextPercent) + RESET);
        turnCalls = 0;
        turnInput = 0;
        turnOutput = 0;
    }

    // Shows the argument a human cares about (command, path...) instead of the raw JSON.
    private String mainArgument(String arguments) {
        try {
            JsonNode node = json.readTree(arguments);
            if (node.isEmpty()) return "";
            JsonNode main = node.has("path") ? node.get("path") : node.elements().next();
            return shorten(main.isTextual() ? main.asText() : main.toString());
        } catch (Exception e) {
            return shorten(arguments);
        }
    }

    private String shorten(String text) {
        List<String> lines = text.strip().lines().toList();
        String first = lines.isEmpty() ? "" : lines.getFirst();
        boolean cut = first.length() > MAX_WIDTH || lines.size() > 1;
        return (first.length() > MAX_WIDTH ? first.substring(0, MAX_WIDTH) : first) + (cut ? " …" : "");
    }
}
