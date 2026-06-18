from pathlib import Path
import pytest


@pytest.fixture
def fake_repo(tmp_path: Path) -> Path:
    """include/exclude 검증용 미니 레포 트리."""
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "guide.md").write_text("# Guide\nhello", encoding="utf-8")
    (tmp_path / "memory").mkdir()
    (tmp_path / "memory" / "notes.md").write_text("# Notes", encoding="utf-8")
    code_dir = tmp_path / "strategy-engine" / "src" / "main" / "kotlin"
    code_dir.mkdir(parents=True)
    (code_dir / "Dsl.kt").write_text("fun parse() {}", encoding="utf-8")
    # 제외 대상들
    build = tmp_path / "strategy-engine" / "build"
    build.mkdir(parents=True)
    (build / "Generated.kt").write_text("class X", encoding="utf-8")
    (tmp_path / ".env").write_text("SECRET=x", encoding="utf-8")
    (tmp_path / "docs" / "key.pem").write_text("-----BEGIN-----", encoding="utf-8")
    return tmp_path
