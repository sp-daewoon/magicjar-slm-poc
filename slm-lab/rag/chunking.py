from dataclasses import dataclass
from pathlib import Path

_CODE_DECL_PREFIXES = ("fun ", "class ", "object ", "interface ", "enum ", "data class ")


@dataclass
class Chunk:
    text: str
    file_path: str
    start_line: int
    end_line: int
    kind: str


def _emit(lines: list[str], start_idx: int, end_idx: int, file_path: str, kind: str) -> Chunk | None:
    body = "\n".join(lines[start_idx:end_idx]).strip("\n")
    if not body.strip():
        return None
    return Chunk(
        text=body,
        file_path=file_path,
        start_line=start_idx + 1,
        end_line=end_idx,
        kind=kind,
    )


def _split_on(text: str, file_path: str, kind: str, is_boundary) -> list[Chunk]:
    lines = text.splitlines()
    boundaries = [i for i, ln in enumerate(lines) if is_boundary(ln)]
    if not boundaries or boundaries[0] != 0:
        boundaries = [0] + boundaries
    chunks: list[Chunk] = []
    for n, start in enumerate(boundaries):
        end = boundaries[n + 1] if n + 1 < len(boundaries) else len(lines)
        c = _emit(lines, start, end, file_path, kind)
        if c:
            chunks.append(c)
    return chunks


def chunk_markdown(text: str, file_path: str) -> list[Chunk]:
    return _split_on(text, file_path, "markdown", lambda ln: ln.lstrip().startswith("#"))


def chunk_code(text: str, file_path: str) -> list[Chunk]:
    def is_decl(ln: str) -> bool:
        s = ln.lstrip()
        return any(s.startswith(p) for p in _CODE_DECL_PREFIXES)

    return _split_on(text, file_path, "code", is_decl)


def chunk_file(path: Path) -> list[Chunk]:
    text = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix == ".md":
        return chunk_markdown(text, str(path))
    return chunk_code(text, str(path))
