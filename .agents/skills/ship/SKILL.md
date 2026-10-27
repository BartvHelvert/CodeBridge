---
name: ship
description: Commit finished work, after asking the user whether to also create a GitHub issue and open a pull request for it. Use when a feature or bug fix is ready to commit, or when the user asks for a commit, issue or PR.
---

# Commit, and optionally file an issue and open a PR

Other sessions edit the main checkout at the same time. Never run `git checkout -- .`, `git reset`,
`git stash`, `git clean` or a rebase there, and never switch branches in it.

## 1. Check that the work is finished

Follow `AGENTS.md`: a feature has a UI test that was run, and the full test suite has been run once.
Anything that wasn't run is reported as such, here and in the PR.

## 2. Ask what to do

Ask the user, with a question tool if the agent has one, unless they already said:

1. **Commit only**
2. **Commit and create an issue**
3. **Commit, create an issue and open a PR**

If the work belongs to an existing issue (the user named one, or it was created earlier in the
conversation), offer to reuse it instead of creating a new one.

## 3. Create the issue

For options 2 and 3, create the issue first with the `issue` skill, so the commit can refer to it.

## 4. Commit

Stage only the files you changed for this work, by path; never `git add -A` or `git add .`, which
would pick up other sessions' edits.

Write the message in the Conventional Commits format described in `CONTRIBUTING.md`, for example
`fix(lsp): show hover Markdown as rendered text`, with a body that explains why. Count the subject
before committing: aim for 50 characters, at most 72, prefix included. Leave class and file names
out of it and move the reason into the body. With an issue, end the message with a `Refs #<N>`
footer. PRs are rebased onto `master`, so every commit lands there by itself: each one should
build, not only the last.

For option 1, stop here. Don't push.

## 5. Push the branch

How depends on where the work was done (see the `start-work` skill):

- **New branch, or new branch + worktree**: `git push -u origin <branch>`. Run it from the worktree
  when there is one.
- **Current branch `master`**: push the commits to a new remote branch without switching branches
  locally. Name it `<type>/<short-slug>`, as in the `start-work` skill.
  ```
  git fetch origin
  git log --format="%h %s%n%b" origin/master..master
  git push origin <tip-sha>:refs/heads/<branch>
  ```
  Everything between `origin/master` and the tip goes into the PR. If that range has commits for
  other work, stop and ask the user how to split them, unless those commits belong to another open PR
  (find it with `gh pr list --search "<M> in:body"`): then stack this PR on that branch with
  `--base <its branch>` and say so in the PR body. Don't rewrite history to separate them.

## 6. Open the PR

Write the body to a file in the scratchpad directory, following `.github/pull_request_template.md`:

- **What this changes**: what it fixes or adds and why, and `Fixes #<N>` on its own line.
- **How it was tested**: the tests added, the test runs and their results, anything checked by hand.
  Report failures and skipped steps as they are.
- **Checklist**: tick only what holds. Strike through a line that doesn't apply, rather than ticking it.

```
gh pr create --base master --head <branch> --title "<type>(<scope>): <subject>" --body-file <file>
```

The title follows the commit format; with one commit, reuse its subject. Give the user the PR URL.
Don't merge it: the user reviews and merges.

## Updating a PR after review

Commit the changes where the work was done, with `Refs #<N>`, and push to the same branch. On
`master`, push the new tip to the PR branch; this must be a fast-forward, so if commits for other work
came in between, ask the user, as in step 5.

## After a merge

PRs are merged by rebasing, which gives every commit a new ID on `master`, so the local commits no
longer count as merged. Check `gh pr view <branch> --json state` says `MERGED` before deleting
anything. Only when the user asks:

- **Worktree**: `git worktree remove ../CodeBridge-<short-slug>`, then `git branch -D <branch>`.
  `-d` refuses, because of the new commit IDs.
- **New branch in the main checkout**: switching back to `master` changes files other sessions may
  be editing, so ask before `git switch master`, then `git branch -D <branch>`.
- **Work committed on `master`**: `git fetch origin`, then compare the trees with
  `git diff --quiet master origin/master`. If they are the same, `git reset --keep origin/master`
  swaps the local commits for the merged ones without touching any file. If they differ, local
  `master` has commits that aren't merged yet, and `git rebase origin/master` rewrites files in the
  shared checkout: ask first.
