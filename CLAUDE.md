# Claude Code Guide: magicJar SLM Lab

This repository is a local SLM research lab derived from magicJar. Treat it as a separate project with separate memory, separate safety rules, and separate GitHub history.

## Identity

- Project name: `magicJar SLM Lab`
- Purpose: test local SLM integration for strategy research, DSL drafting, explanations, and read-only tool use.
- Non-purpose: production trading, live order automation, real-account mutation, or release work for the original magicJar repository.

## Safety Rules

1. Keep `MAGICJAR_MODE=MOCK` and `MAGICJAR_ALLOW_REAL=false` for lab runtime.
2. Do not introduce code that lets SLM output place orders, publish live order Kafka topics, activate strategies, or mutate real account state.
3. SLM output is a draft or hypothesis. Validate it with code-owned checks before surfacing it as usable.
4. Provider-specific code stays outside `domain`.
5. If a task touches order, risk, KIS credentials, Kafka order topics, or consumer execution paths, stop and state the safety impact before changing code.
6. Prefer local and read-only data sources for PoC. Do not add real secrets to examples, docs, tests, or logs.

## Preferred Architecture

The first provider boundary is:

```text
api/strategyresearch/StrategyResearchAiTextClient
```

Add SLM providers behind that interface first. Keep prompt building, JSON parsing, instrument matching, and validation inside magicJar-owned services.

Recommended early order:

1. `OllamaStrategyResearchAiTextClient`
2. provider config such as `magicjar.strategy-research.ai-discovery.provider=ollama`
3. tests for timeout, malformed JSON, disabled provider, and validation warnings
4. frontend wording cleanup from provider-specific terms to generic AI/SLM provider language

## Working Style

- Read existing code before changing it.
- Keep PoC changes narrow and reversible.
- Add tests around provider selection and failure handling.
- Keep docs honest about what is validated and what is only model-generated.
- Record major decisions in `memory/slm_poc_decisions.md`.

## Runtime Notes

Use separate ports and Docker project names. The lab should not share containers, volumes, Kafka topics, or Redis keys with a running original magicJar stack.

Suggested default ports:

- frontend: `13000`
- api: `18080`
- batch: `18081`
- consumer: `18082`
- postgres: `15432`
- redis: `16379`
- kafka: `19092`
- ollama: `11434` or `11435`

## Response Tone

Use Korean with clear engineering judgment unless the user asks otherwise. Be direct about safety boundaries, but keep the lab playful and experimental.
