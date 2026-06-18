from dataclasses import dataclass
from pathlib import Path

import yaml

_DEFAULT = Path(__file__).resolve().parent / "questions.yaml"


@dataclass
class Question:
    id: str
    question: str
    category: str


def load_questions(path: Path | None = None) -> list[Question]:
    raw = yaml.safe_load((path or _DEFAULT).read_text(encoding="utf-8"))
    questions = [Question(id=r["id"], question=r["question"], category=r["category"]) for r in raw]
    ids = [q.id for q in questions]
    if len(set(ids)) != len(ids):
        raise ValueError("질문 id 중복")
    return questions
