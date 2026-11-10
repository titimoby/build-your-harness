# Step 07 — File tools

Step 06 can approve or refuse a shell command, but a `sed -i 's/…/…/' file` is hard to judge. This step adds two dedicated
tools, `write_file` and `edit_file`, so that each kind of action gets its own permission: read-only shell commands still
run freely, and every file change asks for approval while **showing what will change**.

## What's in the code

- `write_file(path, content)` creates or overwrites a file. Approval says whether it is a **new file** or an **overwrite**
  (with the line count before and after, highlighted), and shows the first lines of the new content.
- `edit_file(path, old_text, new_text)` replaces **exact** text (no regular expression) that must appear **exactly once**:
  the edit lands exactly where the model meant it. Approval shows it as a red / green diff, before anything is written.
- `isReadOnly()` now receives the tool name: the bash allowlist is unchanged, and writing files always needs approval.
  Permissions follow the tool, not a parsed string.
- The errors of `edit_file` are written *for the model*: they say what to do next ("Read the file again and copy the text
  exactly", "Include more surrounding lines"). A tool error message is a prompt.
- The system prompt tells the model to change files with these tools, never with `sed` or shell redirections.
- The `tool()` helper now accepts several parameters: `ToolFunction` receives the parsed JSON arguments.

## Run

In a real terminal, from a throwaway directory (see [step 05](../05-bash/) for the keys):

```bash
jbang /path/to/this/repo/07-file-tools/Agent.java --inspect
```

## Try it

| Prompt | What you should see |
|---|---|
| "Add a header comment summarizing the file to <some file>." | An `edit_file` call; the red / green diff is shown **before** writing; `y` → the file is changed |
| The same prompt with step 06 | A `sed -i` or `cat > … <<EOF` command to approve, with no way to see the result beforehand |
| "In <some file>, replace X by Y." where X appears several times | The model gets "appears several times… Include more surrounding lines" and narrows its edit |
| "Create a NOTES.md listing the files of this project." | A `write_file` call; approval shows `new file NOTES.md (N lines)` and the first lines |
| "Rewrite the README from scratch." | Approval warns `overwrites README.md (X lines → Y lines)` in yellow: you see that the whole file is replaced before saying `y` |
| "Explore this project and summarize it." | Still no question: read-only shell commands keep running freely |

Things to watch: does the model really prefer `edit_file` over `sed`? How many tokens does an edit cost compared to
rewriting the whole file?

## Limits

- The model can still edit files through the shell if it ignores the system prompt; such commands then ask for approval
  like any other non-read-only command, without a diff.
- `write_file` replaces the whole file: approving it means trusting its full content.
- The allowlist holes of step 06 are still there, and isolation is still the real defense.
