import pytest
from rag.retrieval import retrieve


@pytest.mark.integration
def test_retrieve_returns_topk_sorted():
    results = retrieve("전략리서치 AI 후보 발굴 흐름", top_k=3)
    assert 1 <= len(results) <= 3
    scores = [r.score for r in results]
    assert scores == sorted(scores, reverse=True)
    assert all(r.file_path and r.file_path != "?" for r in results)
