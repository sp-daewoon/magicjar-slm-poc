---
name: slm-lab-boundary
description: Use before changing SLM provider code, prompt contracts, runtime compose files, order/risk paths, or project memory in magicJar SLM Lab.
---

# slm-lab-boundary

This skill keeps the lab separated from production trading behavior.

## Before Work

Read:

- `README.md`
- `CLAUDE.md`
- `CODEX.md`
- `memory/MEMORY.md`
- `memory/slm_poc_safety.md`

Then write a short boundary note:

```text
Lab goal:
Touched areas:
Trading/order impact:
Runtime isolation impact:
Validation plan:
```

## Rules

- AI/SLM output is always a draft or hypothesis.
- magicJar-owned code validates output.
- Users decide what to save or run.
- No real-account mutation.
- No live order publication.
- No strategy auto-activation.

## Verification

Provider work should include tests for disabled provider, timeout/error mapping, malformed output, and schema/JSON parsing when applicable.
