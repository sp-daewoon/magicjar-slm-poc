from rag import config  # noqa: F401  (pythonpath 확인용)
from eval.questions import load_questions, Question


def test_loads_thirty_questions():
    qs = load_questions()
    assert len(qs) == 30
    assert all(isinstance(q, Question) for q in qs)


def test_ids_are_unique_and_fields_present():
    qs = load_questions()
    ids = [q.id for q in qs]
    assert len(set(ids)) == 30
    assert all(q.question.strip() and q.category in {"system", "dsl"} for q in qs)
