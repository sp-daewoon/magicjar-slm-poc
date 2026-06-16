# SLM PoC Safety Boundary

## Runtime Boundary

Use these defaults for lab work:

```env
MAGICJAR_MODE=MOCK
MAGICJAR_ALLOW_REAL=false
MAGICJAR_AI_ENABLED=true
```

Do not run this lab against production accounts or production volumes.

## Model Output Boundary

Allowed:

- candidate hypotheses
- strategy explanations
- DSL drafts
- JSON summaries
- validation repair suggestions
- read-only analysis

Forbidden:

- order placement
- live order Kafka publication
- real account mutation
- strategy auto-activation
- bypassing DSL parser or risk validation
- hiding model uncertainty from the user

## Data Boundary

Preferred:

- local persisted data
- read-only DB state
- fixtures
- mock/VPS-safe credentials after explicit review

Forbidden in tracked files:

- real KIS keys
- account numbers
- API secrets
- bearer tokens
- raw private prompts containing sensitive account state

## Review Trigger

Pause and review before changing:

- `consumer/order`
- `consumer/risk`
- Kafka order topics
- KIS credential handling
- real trading consent
- kill switch logic
- `MAGICJAR_ALLOW_REAL`
- DB migrations touching order/account/position tables
