import rag.pipeline as pipeline


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
