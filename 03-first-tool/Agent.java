///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS dev.langchain4j:langchain4j-open-ai:1.22.0
//DEPS org.slf4j:slf4j-simple:2.0.17
//SOURCES ../common/Display.java
//FILES simplelogger.properties=../common/simplelogger.properties

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
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
        You are a concise assistant for software developers.
        Answer in at most three sentences unless asked otherwise.
        """;

// A tool is only a description sent to the model: a name, what it does, and its parameters.
final ToolSpecification GET_TIME = ToolSpecification.builder()
        .name("get_time")
        .description("Returns the current local date and time")
        .build();

long totalInput = 0;
long totalOutput = 0;

Display display; // console rendering (common/Display.java), not part of the harness

void main(String[] args) {
    boolean inspect = List.of(args).contains("--inspect");
    display = new Display(inspect);
    ChatModel model = OpenAiResponsesChatModel.builder()
            .baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL)
            .store(false) // stay stateless: the server keeps nothing, we resend the history
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

        // The model cannot run anything: it can only ASK us to run a tool.
        if (answer.hasToolExecutionRequests()) {
            for (var call : answer.toolExecutionRequests()) {
                display.toolCall(call);
                String result = execute(call);
                display.toolResult(result);
                history.add(ToolExecutionResultMessage.from(call, result));
            }
            // Second round trip, so the model can use the result. Step 4 turns this "if" into a "while".
            answer = send(model, history);
        }
        display.answer(answer.text());
    }
}

AiMessage send(ChatModel model, List<ChatMessage> history) {
    var request = ChatRequest.builder().messages(history).toolSpecifications(GET_TIME).build();
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
    return switch (call.name()) {
        case "get_time" -> LocalDateTime.now().withNano(0).toString();
        default -> "Error: unknown tool " + call.name();
    };
}

String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
}
