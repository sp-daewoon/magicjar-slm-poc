import eval.judge as judge
from eval.run_eval import build_ragas_dataset


def test_judge_pair_parses_json(monkeypatch):
    monkeypatch.setattr(
        judge, "_claude",
        lambda p: 'noise {"winner":"gemma","gemma_score":4,"reason":"명확"} trailing',
    )
    out = judge.judge_pair("q", "opus", "gemma")
    assert out["winner"] == "gemma"
    assert out["gemma_score"] == 4


def test_build_ragas_dataset_columns():
    rows = [{"question": "q", "gemma_answer": "a", "contexts": ["c1"], "opus_answer": "g"}]
    ds = build_ragas_dataset(rows)
    assert set(ds.column_names) == {"question", "answer", "contexts", "ground_truth"}
    assert ds[0]["ground_truth"] == "g"
