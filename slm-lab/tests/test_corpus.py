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
    names = {f.name for f in files}
    # build artifact, .env, and .pem must be excluded from results
    assert "Generated.kt" not in names
    assert not any(f.name == ".env" for f in files)
    assert not any(f.suffix == ".pem" for f in files)
    # none of the discovered files may live under an excluded build/ dir
    rels = [f.relative_to(fake_repo).as_posix() for f in files]
    assert all("build/" not in r for r in rels)


def test_returns_sorted_absolute_paths(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    assert all(f.is_absolute() for f in files)
    assert files == sorted(files)
