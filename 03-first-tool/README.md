# Step 03 — A first tool

The model never runs anything. It can only **ask** the harness to run a tool, and the harness decides, executes, and sends
the result back. This step adds one trivial tool, `get_time`, and a single round trip.

## What's in the code

- A `ToolSpecification`: a name, a description and parameters. That description is all the model knows about the tool.
- When the answer contains tool execution requests, the harness runs each one (`execute`) and appends a
  `ToolExecutionResultMessage` to the history.
- A second call lets the model use the result. Only **one** round trip: if the model asks for another tool, this step can't
  follow (that's step 04).

## Run

```bash
jbang 03-first-tool/Agent.java --inspect
```

## Try it

| Prompt | What you should see |
|---|---|
| "What time is it?" | `● get_time` then `└ <date and time>`, then the answer. Two model calls for one question |
| "Hello" | No tool call: the model decides when it needs a tool |
| "What time is it, and what time will it be in 3 hours? Check with the tool each time." | If the model asks for the tool a second time, the answer is `null`: one round trip isn't enough |

In `inspect.log`: `tools` in the request, a `function_call` item in the response, then a `function_call_output` item in the
next request.

**Next:** [Step 04 — The agentic loop](../04-agentic-loop/)
