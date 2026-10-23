# Build your own agent harness

A model only completes text. What turns it into an agent is the **harness** around it: the conversation history, the loop,
the tools, the guardrails, the context management. This repository builds a small **coding agent in Java**, one concept at
a time, writing every part of the harness ourselves.

Each step is a single runnable file, and each new step comes with an article. The difference between two consecutive steps
is exactly what that article explains.

## The steps

| Step | What it adds |
|---|---|
| [01 — Minimal chat](01-chat/) | The model is stateless: the conversation is a list we keep and resend every turn |

More steps will be added here, one per article.

## Requirements

- **Java 25** and **[JBang](https://www.jbang.dev/)**: each step runs with `jbang <step>/Agent.java`, no build project needed.
- An endpoint implementing the **OpenAI Responses API** (`/v1/responses`) and its API key.

## Configuration

| Variable | Default |
|---|---|
| `LLM_BASE_URL` | `https://api.openai.com/v1` |
| `LLM_API_KEY` | — |
| `LLM_MODEL` | `gpt-6-luna` |

## Running a step

```bash
export LLM_API_KEY=...
```

```bash
jbang 01-chat/Agent.java
```

Type `exit` to quit. Add `--inspect` to see the raw JSON exchanged with the API: it is written to `inspect.log` in the current
directory. Watch it from a second terminal:

```bash
tail -F inspect.log
```

## What is (and is not) the harness

- `Agent.java` in each step **is** the harness. Nothing is hidden in a framework.
- [LangChain4j](https://docs.langchain4j.dev/) is used through its **low-level API** only (`ChatModel`, `ChatMessage`…):
  it handles HTTP and JSON, nothing else.
- `common/` holds the display code, kept out of the harness on purpose: `Display.java` prints the conversation to the
  console, so the step code stays focused on the harness itself.

## License

[Apache License 2.0](LICENSE)
