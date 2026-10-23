# Step 01 — Minimal chat

The model is **stateless**. It does not remember the previous turn: the "conversation" is a list of messages that *we*
keep and send in full with every request. This step is nothing more than that list and a loop around it.

## What's in the code

- An `OpenAiResponsesChatModel` (LangChain4j) pointing at any endpoint implementing the Responses API.
- `store(false)`: we ask the server to keep nothing. The Responses API *could* keep the conversation server-side
  (`previous_response_id`), but then we would lose control of the context, which later steps rely on.
- A `List<ChatMessage> history`: each user message and each answer is appended, and the whole list is sent every turn.

## Run

```bash
jbang 01-chat/Agent.java --inspect
```

In a second terminal, `tail -F inspect.log` shows the raw JSON of each request and response.

## Try it

| Prompt | What you should see |
|---|---|
| "My name is Alice." then "What is my name?" | The model answers correctly. In `inspect.log`, the second request contains three items: your first message, its answer, your question |
| Replace line 38 `AiMessage answer = model.chat(history).aiMessage();` by `AiMessage answer = model.chat(List.of(UserMessage.from(input))).aiMessage();`, run again, same two prompts | The model can't know your name: each request now only carries the current message (one item in `input`) |

In the JSON, note `"store" : false` and the full history in `input`.

Note: commenting out `history.add(answer);` alone is not enough. Your first message is still in the list, so the model still "knows" the name; it only loses its own previous answers.

