# Codex Guide: magicJar SLM Lab

This repo is not the production magicJar workspace. It is a clean SLM PoC workspace with its own memory.

## Prime Directive

Build a safe local SLM lab where models can draft, explain, rank, and propose hypotheses while magicJar code performs validation and the user remains the decision maker.

## Hard Boundaries

- Never treat AI/SLM output as a BUY/SELL command.
- Never wire SLM output to real order execution, live order Kafka topics, strategy auto-activation, or real account writes.
- Keep runtime defaults mock-only:
  - `MAGICJAR_MODE=MOCK`
  - `MAGICJAR_ALLOW_REAL=false`
- Do not add real KIS, Anthropic, OpenAI, Gemini, or other secrets to tracked files.
- If a task needs external API documentation or current model behavior, verify against official docs or the local provider endpoint before assuming.

## First Integration Path

Start with the existing provider interface:

```text
StrategyResearchAiTextClient
```

The preferred first implementation is an Ollama HTTP adapter for local models:

```text
OllamaStrategyResearchAiTextClient
```

The model should return text only. Services must parse, validate, filter, and warn.

## Files To Keep Current

- `memory/MEMORY.md`: living project memory
- `memory/slm_poc_decisions.md`: architecture decisions and rationale
- `memory/slm_poc_safety.md`: safety boundary notes
- `.env.slm-poc.example`: mock-only runtime defaults
- `README.md`: user-facing lab overview

## Verification Expectations

For SLM provider work, prefer focused tests:

- provider selection from config
- disabled provider behavior
- Ollama timeout/error mapping
- fenced/plain JSON parsing
- malformed output handling
- no write/order side effects

For frontend work, verify that provider-specific language does not mislead users into thinking model output is a recommendation.

## Git Hygiene

This repo is intended to have a new GitHub history. Do not import old operational memory from the original magicJar repo. If old code is useful, keep it as code context only and rewrite all project memory for this lab.
