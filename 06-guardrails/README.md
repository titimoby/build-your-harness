# Step 06 — Guardrails

The harness is the only security boundary: the model is not one. This step keeps step 05 exactly as it is, a single `bash`
tool, and adds three guardrails around it.

1. **A read-only allowlist.** Read-only programs (`ls`, `cat`, `head`, `tail`, `wc`, `grep`, `pwd`, `tree`,
   `git status/diff/log`) run without asking. A chained command (`&&`, `||`, `;`, `&`, `|`) runs without asking only if
   **every** part is read-only: `ls -la && cat README.md | head` passes, `ls; rm -rf ~` or `cat a | sh` ask.
   Anything that could hide another command always asks: `$(…)`, backticks, `<` and `>` redirections, line breaks.
   When the split is wrong (a `;` inside quotes), the harness errs on the safe side and asks.
2. **Human approval for every other command.** You see the full command before it runs. A refusal is sent back to the
   model as the tool result.
3. **A limit on tool rounds**: at most 25 rounds per request. When it is reached, the harness stops the loop, but still
   gives a result to every requested call, otherwise the next request would be invalid.

The system prompt also asks the model for one simple command per call. That is an instruction, not a guardrail: the
allowlist must stay safe even if the model ignores it.

## What's in the code

- `isReadOnly(call)` parses the command, rejects anything with a hidden command, splits chained commands and checks each part.
- `execute()` asks `isReadOnly()` first, then `display.confirm(call)` if needed, and only then runs the tool.
- The agentic loop counts its rounds and stops at `MAX_TOOL_ROUNDS`.

## Run

In a real terminal, from a throwaway directory (see [step 05](../05-bash/) for the keys):

```bash
jbang /path/to/this/repo/06-guardrails/Agent.java --inspect
```

When a command needs your approval, the header shows `[approval needed]` and the conversation `Allow? [y]es / [n]o`:
`y` approves, `n` or `Esc` refuses.

## Try it

| Prompt | What you should see |
|---|---|
| "Explore this project and summarize it." | `ls`, `cat`, `head`… run without any question |
| "Clean up the temporary files of this project." | The full command (`rm …`) is shown **before** running; answer `n` → `✗ denied`, and the model adapts |
| "List the files, then show the start of the README, in one command." | `ls && head README.md` runs without asking: every part is read-only |
| "Delete the build directory and list what's left, in one command." | `rm -rf build && ls` asks: one part is enough |
| "Which Java version is installed?" | `java -version` asks: `java` is not in the list (it can run code) |
| "Add a header comment to <some file>." | The model edits through the shell (`sed -i …`, `cat > … <<EOF`): you are asked to approve a command, without seeing what it will actually change |

To see the round limit in action, lower `MAX_TOOL_ROUNDS` to 2.

## Limits

- **Approving a shell command is not the same as knowing what it changes.** A `sed -i 's/…/…/' file` is hard to judge:
  you approve blindly, or refuse everything. The next step addresses this.
- **An allowlist is a trade-off, not a guarantee.** Known holes: `git diff --output=file` writes a file;
  `cat ~/.ssh/id_rsa` is "read-only" but sends a secret to the model provider.
- Models often append `2>/dev/null`: the `>` makes these commands ask for approval.
- **The real defense is isolation** (container, sandbox, dedicated user): guardrails in the harness reduce risk, they don't remove it.

