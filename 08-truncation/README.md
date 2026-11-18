# Step 08 — Truncating tool output

Every tool result stays in the history and is resent with every call (step 02). Until now, a single `cat` of a big file
or a long build log went entirely into the context, and every later call paid for it again. This step caps the size of
each tool result: the first step in managing the context window, the agent's scarcest resource.

## What's in the code

- `MAX_TOOL_OUTPUT` (10,000 characters, roughly 2,500 tokens) caps every tool result, whatever the tool.
- `truncate()` keeps the **start and the end** of a long output and cuts the middle: the end is often where the error, the
  summary line or the exit code is.
- The cut is replaced by a line written **for the model**: how much was cut, and how to see the rest (`head`, `tail`,
  `grep`). Like the error messages of step 07, the truncation notice is a prompt.
- One line changes in the loop: `truncate(execute(call))`. The harness applies it to every tool; no tool has to care.
- The display adds `· truncated` to the summary of a cut result, so you see when the model got only part of an output.

## Run

In a real terminal, from a throwaway directory (see [step 05](../05-bash/) for the keys):

```bash
jbang /path/to/this/repo/08-truncation/Agent.java --inspect
```

## Try it

| Prompt | What you should see |
|---|---|
| "What does <a big file> say about <something in its middle>?" | The `cat` result ends with `· truncated`; in the Inspector, the `[... N characters cut ...]` line. The model then narrows down with `grep` |
| "Show me the full content of <a big file>." | Truncated first, then the model reads the file again **in slices**, each under the limit (with Python if `sed` and pipes are discouraged): it does what you asked |
| "What is at the end of <a big file>? Show it with cat." | The answer is right: the end of the output was kept |
| "Explore this project and summarize it." | Nothing changes for normal outputs: they are far below the limit |

Things to watch: the context percentage in the status bar after reading a big file, compared with step 07.

## Limits

- The limit counts characters, not tokens: a rough but free estimate (about 4 characters per token for English text and code).
- The middle of a long output is lost for this call: if the answer was there, the model needs another, narrower command.
- A file read through bash and a 10,000-line log are cut the same way; real agents often pair this with a dedicated read
  tool that takes a line range.
- Truncation guards against an *accidental* flood, it is not a budget: a model that wants the whole file reads it in
  slices, and every slice stays in the history.
- This caps one result, not the conversation: many reasonable outputs still fill the context window, request after request.

