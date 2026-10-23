///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS dev.langchain4j:langchain4j-open-ai:1.22.0
//DEPS org.slf4j:slf4j-simple:2.0.17
//SOURCES ../common/Display.java
//FILES simplelogger.properties=../common/simplelogger.properties

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
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

    // The system prompt is just the first message of the history: it is resent every turn too.
    List<ChatMessage> history = new ArrayList<>();
    history.add(SystemMessage.from(SYSTEM_PROMPT));

    display.banner(MODEL, BASE_URL);
    while (true) {
        String input = display.readUserInput();
        if (input == null || input.equals("exit")) break;
        history.add(UserMessage.from(input));
        var response = model.chat(history);
        AiMessage answer = response.aiMessage();
        history.add(answer);
        display.answer(answer.text());

        // Every turn pays again for the whole history: input tokens grow even if we only say "ok".
        var usage = response.tokenUsage();
        totalInput += usage.inputTokenCount();
        totalOutput += usage.outputTokenCount();
        double cost = (totalInput * PRICE_INPUT + totalOutput * PRICE_OUTPUT) / 1_000_000;
        int contextSize = usage.inputTokenCount() + usage.outputTokenCount();
        display.usage(usage.inputTokenCount(), usage.outputTokenCount(), cost, 100.0 * contextSize / CONTEXT_WINDOW);
    }
}

String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
}
