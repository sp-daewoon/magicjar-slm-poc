# magicJar SLM Lab Memory

This is a fresh project memory for the SLM PoC repository.

## Project Identity

magicJar SLM Lab is a separate repository derived from the magicJar codebase. Its goal is to explore local SLM integration, especially Ollama/Gemma-style models, without changing the production release line.

## Current Direction

- Build SLM provider support behind existing AI text boundaries.
- Start with strategy research candidate discovery.
- Keep output structured, validated, and user-controlled.
- Prefer local Ollama-compatible HTTP APIs for the first provider.
- Keep all runtime defaults mock-only.

## Non-Negotiables

- AI/SLM has no trading authority.
- No live order execution.
- No strategy auto-activation from model output.
- No real KIS credentials in tracked files.
- No shared runtime ports, containers, volumes, Kafka, Redis, or DB with production/dev magicJar.

## Useful Integration Points

- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategyresearch/StrategyResearchAiTextClient.kt`
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategyresearch/StrategyResearchAiDiscoveryService.kt`
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategybuilder/StrategyBuilderAiGenerateService.kt`
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/mcp/`

## First PoC Goal

Connect a local Ollama-compatible model as a `StrategyResearchAiTextClient` provider and use it to produce JSON candidate hypotheses. magicJar remains responsible for parsing, validation, instrument matching, and warnings.

## Open Questions

- Which local model should be the baseline: `gemma3`, `gemma2`, `qwen`, or another small model?
- Should the lab use host Ollama or a Dockerized Ollama service?
- Should the PoC run only API/frontend first, with consumer disabled?
- Which quality metrics matter most: JSON validity, candidate usefulness, latency, or repairability?
