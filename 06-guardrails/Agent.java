///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS dev.langchain4j:langchain4j-open-ai:1.22.0
//DEPS org.slf4j:slf4j-simple:2.0.17
//SOURCES ../common/TuiDisplay.java
//FILES simplelogger.properties=../common/simplelogger.properties
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.1
//DEPS dev.tamboui:tamboui-toolkit:0.6.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.6.0

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;

// Any endpoint implementing the OpenAI Responses API (/v1/responses).
final String BASE_URL = env("LLM_BASE_URL", "https://api.openai.com/v1");
final String API_KEY = env("LLM_API_KEY", "none");
final String MODEL = env("LLM_MODEL", "gpt-6-luna");

// Prices and window size are provider-specific: the API never tells us, we have to know them.
final double PRICE_INPUT = Double.parseDouble(env("LLM_PRICE_INPUT", "0.10"));   // $ per million tokens
final double PRICE_OUTPUT = Double.parseDouble(env("LLM_PRICE_OUTPUT", "0.50")); // $ per million tokens
final int CONTEXT_WINDOW = Integer.parseInt(env("LLM_CONTEXT_WINDOW", "1050000"));

final String SYSTEM_PROMPT = """
        You are a coding agent working in the current directory.
        Use the bash tool to explore, run and modify the project. Be concise.
        Some actions need the user's approval: if one is denied, do not retry it, ask the user instead.
        Prefer one simple command per bash call, without chaining, pipes or redirections.
        """;

// A tool = what we tell the model (spec) + what we actually run.
interface ToolFunction {
    String run(String argument) throws Exception;
}

record Tool(ToolSpecification spec, ToolFunction function) {}

final ObjectMapper json = new ObjectMapper();

// One generic tool replaces list_files, read_file... and everything else the shell can do.
final Map<String, Tool> TOOLS = Map.of(
        "bash", tool("bash", "Runs a bash command in the current directory and returns its output", "command", this::bash));

// Guardrails: the harness, not the model, is the security boundary.
final int MAX_TOOL_ROUNDS = 25; // per user request: a runaway loop costs time and money
// Read-only programs run without asking. Any other command needs the user's approval.
final Set<String> READ_ONLY = Set.of("ls", "cat", "head", "tail", "wc", "grep", "pwd", "tree");
final Pattern HIDDEN_COMMANDS = Pattern.compile("[`$<>\\n]"); // substitutions, redirections, extra lines

long totalInput = 0;
long totalOutput = 0;

TuiDisplay display; // terminal UI (common/TuiDisplay.java), not part of the harness

void main(String[] args) {
    boolean inspect = List.of(args).contains("--inspect");
    display = new TuiDisplay(inspect);
    ChatModel model = OpenAiResponsesChatModel.builder()
            .baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL)
            .store(false) // stay stateless: the server keeps nothing, we resend the history
            .include(List.of("reasoning.encrypted_content")) // ...including the model's reasoning between loop turns
            .logRequests(inspect).logResponses(inspect) // raw JSON on the wire, written to inspect.log
            .build();

    List<ChatMessage> history = new ArrayList<>();
    history.add(SystemMessage.from(SYSTEM_PROMPT));

    display.banner(MODEL, BASE_URL);
    while (true) {
        String input = display.readUserInput();
        if (input == null || input.equals("exit")) break;
        history.add(UserMessage.from(input));
        AiMessage answer = send(model, history);

        // The agentic loop, now bounded: at most MAX_TOOL_ROUNDS rounds of tool calls per request.
        int rounds = 0;
        while (answer.hasToolExecutionRequests() && rounds++ < MAX_TOOL_ROUNDS) {
            for (var call : answer.toolExecutionRequests()) {
                display.toolCall(call);
                String result = execute(call);
                display.toolResult(result);
                history.add(ToolExecutionResultMessage.from(call, result));
            }
            answer = send(model, history);
        }
        if (answer.hasToolExecutionRequests()) {
            // Limit reached: every requested call still needs a result, or the next request is invalid.
            for (var call : answer.toolExecutionRequests()) {
                history.add(ToolExecutionResultMessage.from(call, "Not executed: tool round limit reached."));
            }
            display.answer("Stopped: more than " + MAX_TOOL_ROUNDS + " tool rounds for one request.");
        } else {
            display.answer(answer.text());
        }
    }
}

AiMessage send(ChatModel model, List<ChatMessage> history) {
    var request = ChatRequest.builder().messages(history).toolSpecifications(TOOLS.values().stream().map(Tool::spec).toList()).build();
    var response = model.chat(request);
    history.add(response.aiMessage());

    // Every call pays again for the whole history, tool calls and results included.
    var usage = response.tokenUsage();
    if (usage != null) { // usage is optional in the API: some providers don't report it
        totalInput += usage.inputTokenCount();
        totalOutput += usage.outputTokenCount();
        double cost = (totalInput * PRICE_INPUT + totalOutput * PRICE_OUTPUT) / 1_000_000;
        int contextSize = usage.inputTokenCount() + usage.outputTokenCount();
        display.usage(usage.inputTokenCount(), usage.outputTokenCount(), cost, 100.0 * contextSize / CONTEXT_WINDOW);
    }
    return response.aiMessage();
}

// The harness, not the model, runs the code.
String execute(ToolExecutionRequest call) {
    Tool tool = TOOLS.get(call.name());
    if (tool == null) return "Error: unknown tool " + call.name();
    try {
        if (!isReadOnly(call) && !display.confirm(call)) {
            return "Denied: the user refused this action.";
        }
        return tool.function().run(call.arguments());
    } catch (Exception e) {
        return "Error: " + e; // the model sees the error and can try something else
    }
}

// Auto-approved only if it can't change anything: nothing hidden ($(...), redirections...), and every
// chained or piped command is read-only. "ls && cat README.md | head" passes, "ls; rm -rf ~" does not.
boolean isReadOnly(ToolExecutionRequest call) throws Exception {
    String command = json.readTree(call.arguments()).path("command").asText();
    if (HIDDEN_COMMANDS.matcher(command).find()) return false;
    for (String segment : command.split("&&|\\|\\||[;|&]")) {
        String part = segment.strip();
        String program = part.split("\\s+")[0];
        if (!READ_ONLY.contains(program) && !part.matches("git (status|diff|log)( .*)?")) return false;
    }
    return true;
}

// Builds a tool that takes a single string parameter.
Tool tool(String name, String description, String parameter, ToolFunction function) {
    var spec = ToolSpecification.builder()
            .name(name).description(description)
            .parameters(JsonObjectSchema.builder().addStringProperty(parameter).required(parameter).build())
            .build();
    return new Tool(spec, arguments -> function.run(json.readTree(arguments).path(parameter).asText()));
}

// Runs any command: execute() decides first whether it needs the user's approval.
String bash(String command) throws Exception {
    Path output = Files.createTempFile("bash", ".log");
    try {
        Process process = new ProcessBuilder("bash", "-c", command)
                .redirectErrorStream(true).redirectOutput(output.toFile()) // stdout + stderr, like a terminal
                .start();
        process.getOutputStream().close(); // no stdin: a command waiting for input fails instead of hanging
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new String(Files.readAllBytes(output)) + "\n[killed: timeout after 30s]";
        }
        return new String(Files.readAllBytes(output)) + "\n[exit code " + process.exitValue() + "]";
    } finally {
        Files.deleteIfExists(output);
    }
}

String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
}
