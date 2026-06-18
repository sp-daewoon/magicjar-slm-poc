# magicJar SLM Lab — RAG Phase 1

```bash
# 0) 인프라 (Qdrant + Phoenix)
docker compose -f ../docker/docker-compose.slm-rag.yml up -d

# 1) 의존성
uv sync

# 2) Ollama 모델 준비
ollama pull gemma3:4b

# 3) 인덱싱 (오프라인, 1회성)
uv run python -m rag.indexing

# 4) 챗 UI
uv run streamlit run rag/app.py --server.port 18501

# 5) 평가
uv run python -m eval.run_eval
```

- Qdrant: http://localhost:16333/dashboard
- Phoenix: http://localhost:16006
- Streamlit: http://localhost:18501
