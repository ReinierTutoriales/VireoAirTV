---
name: agent-governance
description: Mandatory rules for all agents. Enforces branch naming, authorship, verification before writing, owner decisions and upstream hygiene. Use before any commit, branch, PR or documentation change.
---

# Agent Governance

Hard constraints that override convenience:

## Branches
- Never create, push, or reference branches whose name contains an AI name, model name, tool name, or non-owner identifier.
- Forbidden patterns: `claude-*`, `gpt-*`, `grok-*`, `ai-*`, `agent-*`, `copilot-*`, `cursor-*`, or any similar.
- Use only short, descriptive, owner-neutral names that describe the change. Default branch: `main`.

## Authorship and signing
- Never set author, committer, or any signature to a name, email, or identity other than the repository owner (`ReinierTutoriales <86568548+ReinierTutoriales@users.noreply.github.com>`).
- Do not insert AI names, tool names, or third-party identities into commit messages, trailers (`Signed-off-by`, `Co-authored-by`), or file metadata.
- Commit messages must describe the change factually; do not attribute the work to an agent.
- Agent environments can sign commits through a global Git config (`commit.gpgsign`, an agent key). Before the first commit run `git config --show-origin --get-regexp 'user\.|gpg|sign'`; set the owner identity and `commit.gpgsign=false` in the repository and check `git cat-file -p HEAD` has no `gpgsig` before pushing.
- Some agent sessions append an attribution footer to every PR description and comment written through their GitHub proxy. From such a session, do not create, edit or comment on PRs or issues: push the branch and give the owner the compare link (`/compare/main...<branch>`). If a footer was already published, remove it by setting the body to an empty string (`PATCH .../pulls/{n}` with `body=""`).
- `.claude/settings.json` turns off Claude Code's automatic attribution.

## Upstream and license
- This repository derives from `jqssun/android-airplay-server` (GPL-3.0). Keep `LICENSE`, upstream credits and the UxPlay/FFmpeg/OpenSSL/libplist notices.
- Do not edit `app/src/main/cpp/third_party/` directly; native engine changes go as patches in `app/src/main/cpp/patches/`. Follow `docs/UPSTREAM.md` for syncs.
- Keep own changes small and isolated so they can be proposed upstream.

## Owner decisions
- Ask the owner before implementing, integrating or changing any behavior that was not explicitly requested, including follow-up "improvements" found during a task. Present the finding and options; wait for the owner's choice.
- Changing `applicationId`, signing, version, or publishing a release requires an explicit owner request.
- Merging, releasing, closing PRs and deleting branches also require the owner's answer.

## Branches, integration and releases
- Unreleased work accumulates on one working branch; integrate into `main` and release only when the owner asks. Fast-forward `main` when the working branch already contains it.
- Release: bump `versionCode`/`versionName` in a `release: X.Y.Z` commit on `main`, then push `release/X.Y.Z`; `apk.yml` builds, signs and publishes `vX.Y.Z` (it also triggers on `v*` tags).
- Agent sessions cannot push tags, force-push or delete remote branches (the proxy answers 403). Do not retry; give the owner the exact `git push origin --delete ...` command.
- `git fetch upstream` brings jqssun's tags (a stale `v0.0.5` pointed at his April commit). Before releasing, compare `git tag -l` with `git ls-remote --tags origin` and delete local tags the origin does not have.

## Long-running jobs
- After a push, PR, merge or workflow dispatch, do not wait, sleep or poll for CI, releases or other remote jobs. Report the SHA, branch/PR and run link, then end the turn.
- Read status with one REST call (`commits/{sha}/check-runs`, `actions/runs`) only when the owner asks or when the next action depends on it.

## Verify before writing
- Read the relevant source, tests, contracts, and existing documentation before proposing or writing any change.
- Do not write code, comments, or documentation for the sake of writing.
- Every line added must be the minimal change that solves a demonstrated need and is traceable to existing code or a clearly labeled, minimal assumption.
- If something is unclear, state the gap explicitly instead of inventing behavior.

## Documentation
- Update documentation only when it reflects verified current behavior.
- Do not invent APIs, policies, guarantees, or future plans.
- Prefer editing existing docs over creating new files.

## Learn from verified errors
- After a regression, failed check or owner correction, identify the violated contract and the cause from code, logs or a reproduction. Mark an unconfirmed cause as a hypothesis.
- Fix the cause and its regression coverage first. Add one concise, reusable lesson to the relevant existing skill only if it prevents recurrence; update or remove superseded guidance instead of growing a log.
- New owner instructions take precedence over historical lessons.
