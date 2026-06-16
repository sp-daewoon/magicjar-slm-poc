# SLM PoC Decisions

## 2026-06-16: Separate Repository Memory

Decision: this lab uses a new Git repository history and new repo-local agent memory instead of reusing the original magicJar memory files.

Reason:

- The lab has a different project identity.
- Existing operational memory can bias agents toward release work.
- SLM PoC safety boundaries are stricter and narrower.

## 2026-06-16: First Provider Boundary

Decision: integrate local SLM providers behind `StrategyResearchAiTextClient` first.

Reason:

- The interface already returns provider-tagged raw text.
- Strategy research already has parsing, matching, warnings, and user-controlled selection.
- It avoids touching live execution paths.

## 2026-06-16: Mock-Only Runtime

Decision: lab runtime defaults are `MAGICJAR_MODE=MOCK` and `MAGICJAR_ALLOW_REAL=false`.

Reason:

- The PoC should measure assistant quality, not trading execution.
- Real order paths add unnecessary risk.

## 2026-06-16: Remove Inherited Workspace Artifacts

Decision: remove the inherited root `CHANGELOG.md` and `_workspace/` tree from the SLM Lab active project tree.

Reason:

- They describe the original magicJar release process, historical tasks, devlogs, ADRs, and baseline cycles.
- Keeping them in the active tree can bias agents toward production release work instead of SLM experimentation.
- Lab decisions should live in `memory/` and future lab-specific docs, not inherited release artifacts.
