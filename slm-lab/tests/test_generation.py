from rag.retrieval import Retrieved
from rag.generation import build_prompt
from rag import config

R = [
    Retrieved(text="fun save() {}", file_path="api/S.kt", score=0.9,
              start_line=10, end_line=12, kind="code"),
]


def test_prompt_contains_system_guard():
    p = build_prompt("저장 흐름?", R)
    assert config.SYSTEM_PROMPT in p


def test_prompt_embeds_context_and_source_path():
    p = build_prompt("저장 흐름?", R)
    assert "fun save() {}" in p
    assert "api/S.kt" in p


def test_prompt_includes_question_last():
    p = build_prompt("저장 흐름?", R)
    assert p.rstrip().endswith("저장 흐름?") or "저장 흐름?" in p.split("질문")[-1]


def test_prompt_handles_empty_context():
    p = build_prompt("아무거나", [])
    assert config.SYSTEM_PROMPT in p
    assert "컨텍스트 없음" in p
