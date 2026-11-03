# Step 05 — Bash

One generic tool replaces all the specific ones: `list_files` and `read_file` disappear, and with `bash` the agent can now
explore, compile, run tests, edit files… anything your shell can do. That power is exactly the problem, and step 06 deals
with it.

> ⚠️ **No safeguard in this step**: the model can run *any* command with your permissions.
> Run it from a throwaway directory, never from a folder holding anything important.

## What's in the code

- The registry of step 04 now holds a single tool, `bash(command)`, run with `ProcessBuilder("bash", "-c", …)`.
- stdout and stderr are merged, like in a terminal; stdin is closed, so a command waiting for input fails instead of hanging;
  a 30-second timeout kills runaway commands.
- The exit code is appended to the result (`[exit code N]`), so the model knows whether the command succeeded.
- The display moves to a terminal UI (`common/TuiDisplay.java`, built with TamboUI). Only the `//DEPS`, `//SOURCES` and
  the type of `display` change: the harness itself is untouched.

## Run

In a real terminal, from a throwaway directory:

```bash
cp -R /path/to/some/project /tmp/sandbox && cd /tmp/sandbox
```

```bash
jbang /path/to/this/repo/05-bash/Agent.java --inspect
```

| Key | Effect |
|---|---|
| `Enter` | Send the message (`exit` quits) |
| Mouse wheel, or `Tab` then ↑ ↓ PgUp PgDn | Scroll the Conversation / Inspector panels; `End` resumes following new lines |
| `Ctrl+O` | Show / hide the Inspector panel (raw JSON, from `inspect.log`) |
| `Ctrl+T` | Select mode: give the mouse back to the terminal to select and copy text; `Ctrl+T` again to scroll |
| `Ctrl+C` | Quit immediately, even while the agent is working |

## Try it

| Prompt | What you should see |
|---|---|
| "Explore this project and summarize it." | Same result as step 04, with commands chosen by the model (`ls`, `find`, `cat`, `head`…) |
| "Which Java version is installed?" | A capability we never coded: `java -version` |
| "How many lines does each Java file have?" | The model composes commands (`wc -l`, `find … -exec`): the whole shell for free |
| "Read the file NOTES.md." (missing) | The model sees the error output and `exit code 1`, then reacts |
| "Run `sleep 60`." | After 30 seconds, the model gets `[killed: timeout after 30s]` |

## Limits

- A huge output (say, the content of a large directory tree) goes entirely into the context: watch the token counter jump.
- Nothing stops a destructive command. Again: throwaway directory.
- If a model call fails (wrong key, network), the UI closes and the stack trace is printed in the restored terminal.

