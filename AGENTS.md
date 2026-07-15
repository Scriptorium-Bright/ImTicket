# Agent Working Rules

## Response and Documentation

- Do not change application or load-test code when the user asks for diagnosis or documentation only.
- Keep console replies within 20 lines whenever practical.
- If an answer would exceed 20 lines or needs cumulative investigation history, write the details to a document and keep the console reply short.
- Prefer updating or creating a focused document under `docs/` for bottleneck analysis, test logs, decision records, and long explanations.
- For cumulative investigations, maintain a concise continuous narrative: connect each result to the prior hypothesis, state only the judgment-changing evidence, and lead naturally to the next experiment. Do not record routine commands, option checks, or operational trivia unless they change the investigation decision.
- In console replies, report only the result, the document path, and the next actionable step.

## Git and Commit Rules

- Do not create commits unless the user explicitly asks for a commit.
- Check `git status --short` before committing, and do not include unrelated user changes.
- Keep documentation under `docs/` uncommitted unless the user explicitly changes that rule.
- Do not add `docs : ...` commits to commit plans under the current project rule.
- Split implementation and verification into separate commits when both are substantial.
- Use the existing commit message style: `feat : ...` for implementation and `test : ...` for verification.
- Do not include phase labels such as `phase`, `1.x`, or checkpoint numbers in commit messages unless the user asks.
- Do not amend, rebase, force-push, reset, or restore files without explicit user approval.
- Before a final commit report, state the commit hash and summarize only the files included in that commit.

## Change Scope

- Preserve dirty worktree changes that were not made by the agent.
- For diagnosis tasks, collect evidence first and avoid speculative code edits.
- Fix clear, safe, localized execution blockers discovered while pursuing the user's requested workflow (for example, an unsupported CLI option) without waiting for a separate approval, unless the user has explicitly restricted all edits. Document the change and validate it proportionally.
- For load-test or bottleneck work, document assumptions, commands, metrics, and next experiments before changing tuning values.
