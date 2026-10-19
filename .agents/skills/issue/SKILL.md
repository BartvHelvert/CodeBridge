---
name: issue
description: Write and create a GitHub issue for a feature or bug fix. Use when the ship skill or the user asks for an issue.
---

# Create an issue

The repository is public: an issue is published the moment it is created. Write it for outside readers.

Never file a security vulnerability as an issue. Tell the user and point to `SECURITY.md`.

## Steps

1. Look for a duplicate: `gh issue list --state all --search "<keywords>"`. If one exists, ask the user
   whether to reuse it, reopening it with `gh issue reopen` when it was closed.
2. Write the body to a file in the scratchpad directory. Follow the matching form in
   `.github/ISSUE_TEMPLATE/`, using its field labels as `###` headings:
   - Bug (`bug_report.yml`): What happened, Steps to reproduce, Extension, IDE, Operating system,
     IDE mode, Logs. Leave out fields you don't know rather than guessing.
   - Feature (`feature_request.yml`): What you'd like, Which extensions need it, Anything else.
   - Add an **Acceptance criteria** checklist: what has to work for the issue to be closed.
3. Keep out local paths under the user's home directory, tokens, and content from private projects.
4. Create it:
   `gh issue create --title "<title>" --label <bug|enhancement|documentation> --body-file <file>`
   The title says what is wrong or wanted in plain words, without a type prefix, for example
   "Hover shows raw Markdown for rust-analyzer" or "Support vscode.window.createWebviewPanel".
5. Tell the user the issue number and URL in one line.
