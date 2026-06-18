from pathlib import Path
from rag.corpus import discover_files

INCLUDE_DIRS = ["docs", "memory", "strategy-engine/src/main/kotlin"]
INCLUDE_EXTS = {".kt", ".md", ".sql"}
EXCLUDE = ["build/", ".env", ".pem"]


def test_includes_only_allowlisted_files(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    names = sorted(f.name for f in files)
    assert names == ["Dsl.kt", "guide.md", "notes.md"]


def test_excludes_patterns(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    joined = " ".join(str(f) for f in files)
    assert "build" not in joined
    assert ".env" not in joined
    assert ".pem" not in joined


def test_returns_sorted_absolute_paths(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    assert all(f.is_absolute() for f in files)
    assert files == sorted(files)
