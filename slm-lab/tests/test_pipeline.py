import rag.pipeline as pipeline


def test_answer_prompt_matches_prompt_sent_to_generate(monkeypatch):
    from rag.retrieval import Retrieved

    fake_retrieved = [
        Retrieved(text="x", file_path="f.kt", score=0.5, start_line=1, end_line=2, kind="code")
    ]
    sent = {}

    def fake_generate(prompt):
        sent["prompt"] = prompt
        return "ANSWER"

    monkeypatch.setattr(pipeline, "setup_phoenix", lambda: None)
    monkeypatch.setattr(pipeline, "retrieve", lambda q, top_k=5: fake_retrieved)
    monkeypatch.setattr(pipeline, "generate", fake_generate)

    res = pipeline.answer("질문?", top_k=3)
    assert res.answer == "ANSWER"
    assert res.prompt == sent["prompt"]
    assert "질문?" in res.prompt


def test_setup_phoenix_instruments_once(monkeypatch):
    calls = {"n": 0}

    def fake_install():
        calls["n"] += 1

    monkeypatch.setattr(pipeline, "_install_instrumentation", fake_install)
    monkeypatch.setattr(pipeline, "_PHOENIX_READY", False)
    pipeline.setup_phoenix()
    pipeline.setup_phoenix()
    assert calls["n"] == 1
    assert pipeline._PHOENIX_READY is True
