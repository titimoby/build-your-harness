///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS dev.langchain4j:langchain4j-open-ai:1.22.0
//DEPS org.slf4j:slf4j-simple:2.0.17
//SOURCES ../common/Display.java
//FILES simplelogger.properties=../common/simplelogger.properties

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;

// Any endpoint implementing the OpenAI Responses API (/v1/responses).
final String BASE_URL = env("LLM_BASE_URL", "https://api.openai.com/v1");
final String API_KEY = env("LLM_API_KEY", "none");
final String MODEL = env("LLM_MODEL", "gpt-6-luna");

Display display; // console rendering (common/Display.java), not part of the harness

void main(String[] args) {
    boolean inspect = List.of(args).contains("--inspect");
    display = new Display(inspect);
    ChatModel model = OpenAiResponsesChatModel.builder()
            .baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL)
            .store(false) // stay stateless: the server keeps nothing, we resend the history
            .logRequests(inspect).logResponses(inspect) // raw JSON on the wire, written to inspect.log
            .build();

    // The model is stateless: this list IS the conversation, and we resend all of it every turn.
    List<ChatMessage> history = new ArrayList<>();

    display.banner(MODEL, BASE_URL);
    while (true) {
        String input = display.readUserInput();
        if (input == null || input.equals("exit")) break;
        history.add(UserMessage.from(input));
        AiMessage answer = model.chat(history).aiMessage();
        history.add(answer);
        display.answer(answer.text());
    }
}

String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
}
