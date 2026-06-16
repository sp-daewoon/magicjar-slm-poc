# slm-check

Use this checklist before opening or merging SLM Lab changes.

1. Confirm `MAGICJAR_MODE=MOCK` and `MAGICJAR_ALLOW_REAL=false` remain the documented defaults.
2. Confirm no tracked file contains real credentials or account identifiers.
3. Confirm model output cannot place orders, publish live order Kafka messages, or activate strategies.
4. Confirm provider-specific code does not enter `domain`.
5. Confirm malformed or partial model output fails gracefully.
6. Confirm user-facing wording calls model output a hypothesis, draft, or analysis.
7. Update `memory/slm_poc_decisions.md` for architecture-level changes.
