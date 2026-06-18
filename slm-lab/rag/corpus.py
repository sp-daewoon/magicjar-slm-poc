from pathlib import Path


def discover_files(
    root: Path,
    include_dirs: list[str],
    include_exts: set[str],
    exclude_patterns: list[str],
) -> list[Path]:
    """allowlist 디렉토리 내에서 허용 확장자 파일만 수집, 제외 패턴은 컷."""
    found: list[Path] = []
    for d in include_dirs:
        base = root / d
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix not in include_exts:
                continue
            rel = str(path.relative_to(root))
            if any(pat in rel or rel.endswith(pat) for pat in exclude_patterns):
                continue
            found.append(path.resolve())
    return sorted(found)
