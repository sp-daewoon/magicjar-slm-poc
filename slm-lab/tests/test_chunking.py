from pathlib import Path
from rag.chunking import Chunk, chunk_markdown, chunk_code, chunk_file


def test_markdown_splits_on_headings():
    text = "# A\nalpha\n# B\nbeta\n"
    chunks = chunk_markdown(text, "x.md")
    assert [c.text.splitlines()[0] for c in chunks] == ["# A", "# B"]
    assert chunks[0].kind == "markdown"
    assert chunks[0].start_line == 1
    assert chunks[1].start_line == 3


def test_markdown_preamble_before_first_heading_kept():
    text = "intro line\n# A\nalpha\n"
    chunks = chunk_markdown(text, "x.md")
    assert chunks[0].text.startswith("intro line")


def test_code_splits_on_top_level_declarations():
    text = "package p\n\nfun a() {}\n\nclass B {\n  fun inner() {}\n}\n"
    chunks = chunk_code(text, "X.kt")
    heads = [c.text.splitlines()[0] for c in chunks]
    assert any(h.startswith("fun a") for h in heads)
    assert any(h.startswith("class B") for h in heads)
    assert all(c.kind == "code" for c in chunks)


def test_chunk_file_dispatches_by_extension(tmp_path: Path):
    md = tmp_path / "d.md"
    md.write_text("# H\nx", encoding="utf-8")
    kt = tmp_path / "C.kt"
    kt.write_text("fun f() {}", encoding="utf-8")
    assert chunk_file(md)[0].kind == "markdown"
    assert chunk_file(kt)[0].kind == "code"
    assert chunk_file(md)[0].file_path == str(md)
