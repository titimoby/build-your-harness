# Step 02 — Tokens and cost

Since every turn resends the whole history, **every turn pays again for everything said so far**. This step adds a system
prompt and makes that cost visible: tokens per turn, session cost, and how full the context window is.

## What's in the code

- A `SystemMessage`, simply the first message of the history: it is resent every turn too.
- `response.tokenUsage()` gives the input and output tokens of each call; the step accumulates them and computes a cost.
- Prices and context-window size come from `LLM_PRICE_INPUT`, `LLM_PRICE_OUTPUT` and `LLM_CONTEXT_WINDOW`: the API never
  reports them, the harness has to know them.

## Run

```bash
export LLM_PRICE_INPUT=0.10 LLM_PRICE_OUTPUT=0.50 LLM_CONTEXT_WINDOW=1050000
```

```bash
jbang 02-tokens/Agent.java
```

After each answer, a grey line shows the calls, tokens, session cost and context usage.

## Try it

| Prompt | What you should see |
|---|---|
| "Hello", then "ok" several times | Input tokens grow at every "ok" while output stays small: you pay for the history, not for "ok" |
| A question that needs a long answer, then "ok" | The next turn costs more input tokens: the long answer is now part of the history |
| "Explain how garbage collection works in Java." | A short answer, three sentences at most, for a topic that usually gets pages: the system prompt applies, because it is part of every request |
| Then "Explain it in detail." | A longer answer: the system prompt says "unless asked otherwise", and now you asked. It sets a default, not a hard limit |

Numbers follow your machine's locale (`0,1%` or `0.1%`). If your provider returns no usage data, `tokenUsage()` may be missing.

