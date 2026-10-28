///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS dev.langchain4j:langchain4j-open-ai:1.22.0
//DEPS org.slf4j:slf4j-simple:2.0.17
//SOURCES ../common/Display.java
//FILES simplelogger.properties=../common/simplelogger.properties
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.1

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
        You are a coding assistant working in the current directory.
        Use the tools to look at the files before answering. Be concise.
        """;

// A tool = what we tell the model (spec) + what we actually run.
interface ToolFunction {
    String run(String argument) throws Exception;
}

record Tool(ToolSpecification spec, ToolFunction function) {}

final ObjectMapper json = new ObjectMapper();

// The tool registry. Step 5 will show that one generic tool can replace all of them.
final Map<String, Tool> TOOLS = Map.of(
        "list_files", tool("list_files", "Lists the entries of a directory", "path", this::listFiles),
        "read_file", tool("read_file", "Reads a text file", "path", this::readFile));

long totalInput = 0;
long totalOutput = 0;

Display display; // console rendering (common/Display.java), not part of the harness

void main(String[] args) {
    boolean inspect = List.of(args).contains("--inspect");
    display = new Display(inspect);
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

        // The agentic loop: as long as the model asks for tools, we run them and call it again.
        while (answer.hasToolExecutionRequests()) {
            for (var call : answer.toolExecutionRequests()) {
                display.toolCall(call);
                String result = execute(call);
                display.toolResult(result);
                history.add(ToolExecutionResultMessage.from(call, result));
            }
            answer = send(model, history);
        }
        display.answer(answer.text());
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
        return tool.function().run(call.arguments());
    } catch (Exception e) {
        return "Error: " + e; // the model sees the error and can try something else
    }
}

// Builds a tool that takes a single string parameter.
Tool tool(String name, String description, String parameter, ToolFunction function) {
    var spec = ToolSpecification.builder()
            .name(name).description(description)
            .parameters(JsonObjectSchema.builder().addStringProperty(parameter).required(parameter).build())
            .build();
    return new Tool(spec, arguments -> function.run(json.readTree(arguments).path(parameter).asText()));
}

String listFiles(String path) throws IOException {
    try (var entries = Files.list(Path.of(path))) {
        return entries.map(p -> Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString())
                .sorted().collect(Collectors.joining("\n"));
    }
}

String readFile(String path) throws IOException {
    return Files.readString(Path.of(path));
}

String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
}
