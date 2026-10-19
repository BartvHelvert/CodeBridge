## Workflow

- Run the full test suite at most once, near completion of an explicitly requested complete change.
  Do not run it for intermediate checkpoints.
- Do not perform broad README, architecture, or guide updates while behavior is still
  being explored. Update documentation after behavior is settled, or when the user explicitly asks
  for documentation.
- After adding a feature, add a UI test and run it for verification.
- Don't create worktrees in a subdirectory of the root repository.
- Before starting a feature or bug fix, ask where to do it (current branch, new branch, or new branch
  + worktree) with the `start-work` skill. When it is ready to commit, ask whether to also create an
  issue and open a PR, with the `ship` skill.
- Commit messages and PR titles follow Conventional Commits, as described in `CONTRIBUTING.md`, for
  example `fix(lsp): show hover Markdown as rendered text`.
- Skills live in `.agents/skills/<name>/SKILL.md`. `.claude/skills/` only holds stubs that point
  there; when you add or change a skill, edit the `.agents` copy and keep the stub's `name` and
  `description` in sync with it.

## API

- Don't use internal, override only, obsolete or deprecated API. Experimental API is allowed.
- Use coroutines instead of futures or callbacks.