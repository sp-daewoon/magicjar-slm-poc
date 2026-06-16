# magicJar SLM Lab

Local SLM proof-of-concept workspace for magicJar.

This repository starts from the magicJar codebase, but it is a separate research project. Its job is to test whether local small language models such as Ollama/Gemma can become useful assistants for strategy research, DSL drafting, explanation, and read-only analysis.

It is not the magicJar release repository.

## Project Boundary

The lab must stay isolated from production trading behavior.

- `MAGICJAR_MODE=MOCK` is the default and expected mode.
- `MAGICJAR_ALLOW_REAL=false` is non-negotiable for PoC work.
- AI/SLM output is never a trading command.
- SLM providers may propose hypotheses, explanations, JSON candidates, or DSL drafts only.
- The application must validate every AI output with existing parser, instrument matching, screener, backtest, or read-only checks before showing it as usable.
- No PoC task may add automatic order submission, live order Kafka publication, strategy auto-activation, or real account mutation.

## First Target

The first useful integration point is `StrategyResearchAiTextClient`.

Recommended provider shape:

```text
StrategyResearchAiTextClient
  -> ClaudeCliStrategyResearchAiTextClient
  -> OllamaStrategyResearchAiTextClient
```

The first PoC should make a local model return structured JSON for strategy research candidate discovery. magicJar should then keep doing the important work: instrument matching, validation, filtering, warnings, and user-controlled selection.

## Runtime Shape

Use a separate Docker project, separate host ports, and a PoC-only environment file.

Suggested host ports:

| Service | Host Port |
| --- | ---: |
| frontend | 13000 |
| api | 18080 |
| batch | 18081 |
| consumer | 18082 |
| postgres | 15432 |
| redis | 16379 |
| kafka | 19092 |
| kafka-ui | 18090 |
| dozzle | 18888 |
| ollama | 11434 or 11435 |

Early PoC runs should prefer `api + frontend + postgres + redis` and avoid running `consumer` unless a task explicitly needs it.

## Local Environment

Start from:

```bash
cp .env.slm-poc.example .env
```

Do not put production KIS credentials into this repository. If any integration needs market data, prefer persisted local fixtures, read-only DB state, or VPS/mock-safe credentials after explicit review.

## Agent Memory

This repo has its own Claude Code and Codex memory:

- `.claude/`
- `CLAUDE.md`
- `CODEX.md`
- `memory/MEMORY.md`

Do not copy operational memory from the original magicJar repo into this lab unless it is deliberately rewritten as PoC policy.

## Success Criteria

A PoC is valuable when it shows one of these things:

- A local SLM can produce parseable, schema-valid candidate JSON.
- A local SLM can draft DSL that passes the existing parser or returns useful repair hints.
- A local SLM can use read-only context to explain results without inventing trades.
- The lab can measure latency, failure modes, token/context size, and output quality.

The cleanest outcome is not "AI trades"; it is "AI drafts, magicJar verifies, the user decides."
