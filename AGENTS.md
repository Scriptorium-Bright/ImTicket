# Agent Working Rules

## Response and Documentation

- Do not change application or load-test code when the user asks for diagnosis or documentation only.
- Keep console replies within 20 lines whenever practical.
- Use standard Korean technical terms or the official terminology used by the relevant framework, protocol, academic field, and domain in every response and document. Do not invent English expressions, arbitrary compound terms, informal shorthand, or agent-specific names.
- When the official terminology is uncertain, describe the phenomenon in plain Korean instead of creating a new term. Add the official English term in parentheses only when its source and meaning are clear.
- Explain a technical term in Korean at its first appearance, then use the same term consistently throughout the response or document. Use project-specific terms only when they are required by the code or API contract, and explain their meaning at first use.
- If an answer would exceed 20 lines or needs cumulative investigation history, write the details to a document and keep the console reply short.
- Prefer updating or creating a focused document under `docs/` for bottleneck analysis, test logs, decision records, and long explanations.
- For cumulative investigations, maintain a concise continuous narrative: connect each result to the prior hypothesis, state only the judgment-changing evidence, and lead naturally to the next experiment. Do not record routine commands, option checks, or operational trivia unless they change the investigation decision.
- In documents and console replies, state concepts and implications directly. Do not use “A가 아니라 B다”, “Not A, but B”, “B이고 not A”, or similar contrastive phrasing. Express distinctions with parallel sentences, tables, explicit criteria, or separate clauses.
- Use established CS, backend, distributed-systems, networking, and web-standard terminology only when it maps to the implementation or decision being documented. Do not invent English compound labels that sound like established concepts. When no established term fits, describe the observed behavior, responsibility, or metric in plain Korean. Keep implementation facts separate from proposed architecture and label the latter as a proposal.
- In console replies, answer the concept the user asked about directly, keep the scope of the question, and do not add unrelated concepts.
- For an approved implementation, identify the chosen solution once, make only the inspections required to apply it safely, then implement and verify it. Do not expand the investigation into adjacent alternatives, repeated design discussion, or extra experiments unless new evidence blocks the chosen solution.
- When an implementation step fails, correct the exact failure and continue from that step. Report the resolved result after verification; do not restart the investigation or reopen completed decisions.
- When writing blog posts, portfolio documents, and result summaries, focus on the work performed, evidence, decisions, and outcomes. Do not add unsolicited limitation, inexperience, self-deprecating, or capability-reducing statements, and do not repeat such caveats.
- When the agent's own implementation, measurement method, wording, or decision is wrong or misaligned, identify it as the agent's error and correct it directly. Do not reframe the agent's mistake as a project limitation, user limitation, or reason to foreground caveats.
- Do not foreground limitations or caveats in ordinary work. State one only when the user requests it or when omitting it would make a factual, safety, or operational claim materially misleading; keep it subordinate to the result and never use it as self-protective framing.
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
- When any requested test, load test, or validation command fails, treat the failure as an execution blocker: identify the root cause, apply a clear and safe localized fix within scope, rerun the affected command, and verify the result before reporting completion. Record the original failure and the fix, but do not merely record the failure and move on. If the fix would be unsafe, materially expand scope, or require new authority, state the exact blocker and ask for direction instead of repeating the failed command.
