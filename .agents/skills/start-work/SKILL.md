---
name: start-work
description: Ask the user where to do a feature or bug fix (current branch, new branch, or new branch in its own worktree) and set that up. Use before editing code for a feature or bug-fix request.
---

# Choose where to work

Before the first edit for a feature or bug fix, ask the user where to do it, unless they already said
in this conversation. Use a question tool if the agent has one. Don't ask for questions,
investigations or edits to agent configuration; work in the current checkout for those.

Offer three options:

1. **Current branch**: work and commit on the branch that is checked out, usually `master`.
2. **New branch**: create a branch in the main checkout. Other sessions edit this checkout at the
   same time, so their commits land on the new branch too, and switching back changes their files.
3. **New branch + worktree**: a separate checkout on a new branch, isolated from other sessions. It
   needs its own Gradle build the first time.

Mention the trade-off with option 2 in the question.

## Branch name

`<type>/<short-slug>`, where the type is the Conventional Commits type the work will be committed
with (see `CONTRIBUTING.md`), for example `fix/hover-raw-markdown` or `feat/webview-panel`. Ask
for a different name only if the user gives one.

## Setting it up

- **Current branch**: nothing to set up. Note the branch name for the `ship` skill.
- **New branch**: `git switch -c <branch>`. This keeps all uncommitted files as they are. Never use
  `git checkout -- .`, `git reset`, `git stash` or anything else that touches other sessions' files.
- **New branch + worktree**: create it next to the repository, never inside it:
  ```
  git worktree add ../CodeBridge-<short-slug> -b <branch> master
  ```
  Do all the work in that directory, using absolute paths. Don't use agent features that create
  worktrees inside the repository, such as directories under `.claude/`.

Tell the user in one line where the work happens, then start.
