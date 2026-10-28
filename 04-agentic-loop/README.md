# Step 04 — The agentic loop

The `if` of step 03 becomes a `while`: as long as the model asks for tools, the harness runs them and calls the model again.
That loop is what turns a chat into an agent. The model now chains calls on its own: list, then read, then answer.

## What's in the code

- A tool registry: `record Tool(ToolSpecification spec, ToolFunction function)`, with `list_files` and `read_file`.
- Tool errors are **returned to the model as results**, not thrown: the model reads them and adapts.
- `include(List.of("reasoning.encrypted_content"))`: with `store(false)`, a reasoning model's hidden reasoning would be lost
  between two turns of the loop. Asking for it encrypted lets the harness send it back with the history.

## Run

From the directory the agent should explore:

```bash
jbang /path/to/this/repo/04-agentic-loop/Agent.java --inspect
```

## Try it

| Prompt | What you should see |
|---|---|
| "Explore this project and summarize it." | Several `list_files` and `read_file` calls in a row, then a summary, with no intervention |
| "Read the file NOTES.md." (missing) | The model gets `Error: java.nio.file.NoSuchFileException` as a result and reacts (lists the directory, asks you…) |
| "Read the README, then read every file it mentions." | Several `read_file` calls in a row, decided by the model: no more `null` answer as in step 03, the loop goes on as long as the model asks |

In `inspect.log`, every request after a tool call contains a `"type": "reasoning"` item with `encrypted_content`.
The token count climbs quickly: each turn of the loop resends the whole history, tool results included.

