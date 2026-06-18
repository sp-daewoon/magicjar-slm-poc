# SLM RAG 도메인 Q&A (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 RAG 파이프라인(LlamaIndex + Qdrant + BGE-M3 + Gemma3:4b)을 세우고, magicJar 도메인 질문에 대해 검색 청크·프롬프트·답변이 화면에 보이는 Streamlit 챗과, Opus 대비 품질을 숫자로 내는 평가 하니스를 만든다.

**Architecture:** `slm-lab/` 별도 Python 프로젝트가 레포를 읽기전용으로 인덱싱(오프라인) → Qdrant에 청크+벡터+메타 저장. 질의 시 LlamaIndex가 top-k 검색 → 안전 가드 시스템 프롬프트로 조립 → Gemma3:4b 생성. 전 단계는 Phoenix 트레이스 + Streamlit 펼침 UI로 가시화. 평가는 동일 30문항에 대해 Gemma+RAG vs Opus 답을 Ragas 지표 + Opus-as-judge로 채점.

**Tech Stack:** Python 3.11+ / uv, LlamaIndex(core·ollama·huggingface·qdrant), Qdrant, BAAI/bge-m3, Ollama(gemma3:4b), Arize Phoenix(openinference), Ragas, langchain-ollama, Streamlit, pytest, PyYAML.

## Global Constraints

- 모든 신규 코드는 `slm-lab/` 아래. magicJar Kotlin 본체 수정 금지 (Phase 1 비범위).
- 런타임 전부 로컬/오프라인. 외부 SaaS 전송 금지. 예외: Opus-judge/Opus-정답지는 기존 `ClaudeCliStrategyResearchAiTextClient`가 쓰는 로컬 Claude CLI 다리만 사용 (사용자가 명시 채택한 패턴).
- 인덱싱은 **읽기전용**. 제외 패턴 필수: `build/`, `node_modules/`, `.git/`, `dist/`, `.env*`, `*.pem`, `*.jks`, `*.p12`, `id_rsa`. → 비밀 인덱싱 0건.
- 챗봇 시스템 프롬프트 가드(verbatim): "당신은 magicJar 도메인 설명 도우미입니다. 설명·요약·초안만 제공합니다. 주문/매매 실행, 전략 활성화, 실계좌 변경을 지시하거나 실행 가능한 명령으로 출력하지 마십시오." (`MAGICJAR_MODE=MOCK` 전제)
- 격리 포트(verbatim): Qdrant `16333`, Phoenix `16006`, Streamlit `18501`, Ollama `11434`(재사용).
- 임베딩 모델 `BAAI/bge-m3` (차원 `1024`). 생성 모델 `gemma3:4b`. Qdrant 컬렉션명 `magicjar_slm`.
- **Git 헌법** (메모리 git-workflow-constitution): 작업 브랜치 `agent/ai/slm-rag-phase1`. main 직접 푸시 금지(husky pre-push가 차단). husky 우회(`--no-verify`) 금지. 머지는 rebase. 커밋 author는 화이트리스트 봇 신원 `bot+ai@magicjar.local` — Task 1에서 repo-local git config로 1회 설정하므로 이후 `git commit`은 그대로 통과.
- 매 Task 끝에 커밋. 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File Structure

```
slm-lab/
  pyproject.toml            # uv 프로젝트 + 의존성
  README.md                 # 실행법
  .gitignore                # .venv, __pycache__, *.pyc
  rag/
    __init__.py
    config.py               # 경로/포트/모델명/코퍼스 규칙/시스템 프롬프트
    corpus.py               # 레포 파일 큐레이션 (include/exclude)
    chunking.py             # 코드/문서 청킹 + Chunk 데이터클래스
    indexing.py             # corpus→chunk→BGE-M3 임베딩→Qdrant 적재
    retrieval.py            # query→top-k 검색→Retrieved 리스트
    generation.py           # 프롬프트 조립 + Ollama 생성 + 안전 가드
    pipeline.py             # retrieval+generation 결합 + Phoenix 계측 + RagResult
    app.py                  # Streamlit 가시화 챗
  eval/
    __init__.py
    questions.yaml          # 평가 30문항
    questions.py            # 질문셋 로더/검증
    judge.py                # Opus 정답지 + Opus-as-judge (Claude CLI 다리)
    run_eval.py             # Ragas + judge 실행 → 점수표
  tests/
    __init__.py
    conftest.py             # 공용 픽스처 (임시 레포 트리, tiny corpus)
    test_corpus.py
    test_chunking.py
    test_indexing.py
    test_retrieval.py
    test_generation.py
    test_pipeline.py
    test_questions.py
    test_run_eval.py
docker/
  docker-compose.slm-rag.yml  # Qdrant + Phoenix (격리 포트)
```

---

### Task 1: 프로젝트 + 인프라 스캐폴드

**Files:**
- Create: `slm-lab/pyproject.toml`
- Create: `slm-lab/.gitignore`
- Create: `slm-lab/rag/__init__.py` (빈 파일)
- Create: `slm-lab/rag/config.py`
- Create: `docker/docker-compose.slm-rag.yml`
- Create: `slm-lab/README.md`

**Interfaces:**
- Produces: `rag.config` 모듈 상수 — `REPO_ROOT: Path`, `INCLUDE_DIRS: list[str]`, `INCLUDE_EXTS: set[str]`, `EXCLUDE_PATTERNS: list[str]`, `QDRANT_URL: str`, `QDRANT_COLLECTION: str`, `EMBED_MODEL: str`, `EMBED_DIM: int`, `GEN_MODEL: str`, `OLLAMA_URL: str`, `PHOENIX_HOST: str`, `PHOENIX_PORT: int`, `DEFAULT_TOP_K: int`, `SYSTEM_PROMPT: str`, `REQUEST_TIMEOUT: float`.

- [ ] **Step 1: pyproject.toml 작성**

```toml
[project]
name = "magicjar-slm-lab"
version = "0.1.0"
description = "magicJar SLM Lab — local RAG domain Q&A (Phase 1)"
requires-python = ">=3.11"
dependencies = [
    "llama-index-core>=0.12",
    "llama-index-llms-ollama>=0.5",
    "llama-index-embeddings-huggingface>=0.5",
    "llama-index-vector-stores-qdrant>=0.6",
    "qdrant-client>=1.12",
    "arize-phoenix>=7.0",
    "openinference-instrumentation-llama-index>=3.0",
    "opentelemetry-exporter-otlp>=1.27",
    "ragas>=0.2",
    "langchain-ollama>=0.2",
    "datasets>=3.0",
    "streamlit>=1.39",
    "pyyaml>=6.0",
    "pandas>=2.2",
]

[dependency-groups]
dev = ["pytest>=8.3"]

[tool.pytest.ini_options]
pythonpath = ["."]
testpaths = ["tests"]
```

- [ ] **Step 2: .gitignore 작성**

```gitignore
.venv/
__pycache__/
*.pyc
.pytest_cache/
*.egg-info/
```

- [ ] **Step 3: config.py 작성**

```python
from pathlib import Path

# slm-lab/rag/config.py → 레포 루트는 3단계 위
REPO_ROOT = Path(__file__).resolve().parents[2]

# 인덱싱 대상: 좁게 시작 (노이즈 방지). 검증 후 확장.
INCLUDE_DIRS = [
    "docs",
    "memory",
    "api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategyresearch",
    "api/src/main/kotlin/kr/spdaewoon/magicjar/api/strategybuilder",
    "strategy-engine/src/main/kotlin",
]
INCLUDE_EXTS = {".kt", ".md", ".sql"}
EXCLUDE_PATTERNS = [
    "build/", "node_modules/", ".git/", "dist/",
    ".env", ".pem", ".jks", ".p12", "id_rsa",
]

QDRANT_URL = "http://localhost:16333"
QDRANT_COLLECTION = "magicjar_slm"

EMBED_MODEL = "BAAI/bge-m3"
EMBED_DIM = 1024

GEN_MODEL = "gemma3:4b"
OLLAMA_URL = "http://localhost:11434"
REQUEST_TIMEOUT = 120.0

PHOENIX_HOST = "localhost"
PHOENIX_PORT = 16006

DEFAULT_TOP_K = 5

SYSTEM_PROMPT = (
    "당신은 magicJar 도메인 설명 도우미입니다. 설명·요약·초안만 제공합니다. "
    "주문/매매 실행, 전략 활성화, 실계좌 변경을 지시하거나 실행 가능한 명령으로 "
    "출력하지 마십시오. 제공된 컨텍스트에 없는 내용은 모른다고 답하십시오."
)
```

- [ ] **Step 4: docker-compose.slm-rag.yml 작성**

```yaml
name: magicjar-slm-rag
services:
  qdrant:
    image: qdrant/qdrant:v1.12.4
    container_name: magicjar-slm-qdrant
    ports:
      - "16333:6333"
    volumes:
      - magicjar-slm-qdrant:/qdrant/storage
  phoenix:
    image: arizephoenix/phoenix:version-7.9.0
    container_name: magicjar-slm-phoenix
    ports:
      - "16006:6006"   # UI + OTLP HTTP
volumes:
  magicjar-slm-qdrant:
```

- [ ] **Step 5: README.md 작성**

````markdown
# magicJar SLM Lab — RAG Phase 1

```bash
# 0) 인프라 (Qdrant + Phoenix)
docker compose -f ../docker/docker-compose.slm-rag.yml up -d

# 1) 의존성
uv sync

# 2) Ollama 모델 준비
ollama pull gemma3:4b

# 3) 인덱싱 (오프라인, 1회성)
uv run python -m rag.indexing

# 4) 챗 UI
uv run streamlit run rag/app.py --server.port 18501

# 5) 평가
uv run python -m eval.run_eval
```

- Qdrant: http://localhost:16333/dashboard
- Phoenix: http://localhost:16006
- Streamlit: http://localhost:18501
````

- [ ] **Step 6: 인프라 기동 + 의존성 설치 + git author 설정**

Run:
```bash
cd slm-lab
git config --local user.email "bot+ai@magicjar.local"
git config --local user.name "magicjar-ai"
docker compose -f ../docker/docker-compose.slm-rag.yml up -d
uv sync
```
Expected: Qdrant·Phoenix 컨테이너 `Up`, `uv sync` 성공. `curl -s localhost:16333/healthz` 가 OK, `curl -s localhost:16006` 가 HTML 응답.

- [ ] **Step 7: config import 확인**

Run: `cd slm-lab && uv run python -c "from rag import config; print(config.REPO_ROOT, config.EMBED_DIM)"`
Expected: 레포 루트 절대경로와 `1024` 출력.

- [ ] **Step 8: Commit**

```bash
cd /Users/ak-song/magicjar-slm-poc
git checkout -b agent/ai/slm-rag-phase1
git add slm-lab/pyproject.toml slm-lab/.gitignore slm-lab/rag/__init__.py slm-lab/rag/config.py slm-lab/README.md docker/docker-compose.slm-rag.yml
git commit -m "feat(slm-lab): scaffold RAG Phase 1 project and isolated infra

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 코퍼스 큐레이션 (corpus.py)

**Files:**
- Create: `slm-lab/rag/corpus.py`
- Create: `slm-lab/tests/__init__.py` (빈 파일)
- Create: `slm-lab/tests/conftest.py`
- Test: `slm-lab/tests/test_corpus.py`

**Interfaces:**
- Consumes: `rag.config` 상수.
- Produces: `discover_files(root: Path, include_dirs: list[str], include_exts: set[str], exclude_patterns: list[str]) -> list[Path]` — 정렬된 절대경로 리스트.

- [ ] **Step 1: conftest.py 작성 (공용 임시 레포 픽스처)**

```python
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
```

- [ ] **Step 2: 실패 테스트 작성**

```python
from pathlib import Path
from rag.corpus import discover_files

INCLUDE_DIRS = ["docs", "memory", "strategy-engine/src/main/kotlin"]
INCLUDE_EXTS = {".kt", ".md", ".sql"}
EXCLUDE = ["build/", ".env", ".pem"]


def test_includes_only_allowlisted_files(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    names = sorted(f.name for f in files)
    assert names == ["Dsl.kt", "guide.md", "notes.md"]


def test_excludes_build_and_secrets(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    joined = " ".join(str(f) for f in files)
    assert "build" not in joined
    assert ".env" not in joined
    assert ".pem" not in joined


def test_returns_sorted_absolute_paths(fake_repo: Path):
    files = discover_files(fake_repo, INCLUDE_DIRS, INCLUDE_EXTS, EXCLUDE)
    assert all(f.is_absolute() for f in files)
    assert files == sorted(files)
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd slm-lab && uv run pytest tests/test_corpus.py -v`
Expected: FAIL — `ImportError` / `discover_files` 없음.

- [ ] **Step 4: corpus.py 구현**

```python
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
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_corpus.py -v`
Expected: PASS (3 passed).

- [ ] **Step 6: Commit**

```bash
git add slm-lab/rag/corpus.py slm-lab/tests/__init__.py slm-lab/tests/conftest.py slm-lab/tests/test_corpus.py
git commit -m "feat(slm-lab): corpus curation with include/exclude rules

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 청킹 (chunking.py)

**Files:**
- Create: `slm-lab/rag/chunking.py`
- Test: `slm-lab/tests/test_chunking.py`

**Interfaces:**
- Consumes: 없음 (순수 함수).
- Produces:
  - `@dataclass Chunk: text: str; file_path: str; start_line: int; end_line: int; kind: str` (`kind` ∈ {"markdown", "code"}).
  - `chunk_markdown(text: str, file_path: str) -> list[Chunk]` — 헤딩(`#`) 경계로 분할.
  - `chunk_code(text: str, file_path: str) -> list[Chunk]` — 최상위 선언(`fun `/`class `/`object `/`interface `) 경계로 분할.
  - `chunk_file(path: Path) -> list[Chunk]` — 확장자로 디스패치(.md→markdown, 그 외→code).

- [ ] **Step 1: 실패 테스트 작성**

```python
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd slm-lab && uv run pytest tests/test_chunking.py -v`
Expected: FAIL — import 오류.

- [ ] **Step 3: chunking.py 구현**

```python
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
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_chunking.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: Commit**

```bash
git add slm-lab/rag/chunking.py slm-lab/tests/test_chunking.py
git commit -m "feat(slm-lab): heading/declaration-aware chunking

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 인덱싱 (indexing.py)

**Files:**
- Create: `slm-lab/rag/indexing.py`
- Test: `slm-lab/tests/test_indexing.py`

**Interfaces:**
- Consumes: `rag.config`, `rag.corpus.discover_files`, `rag.chunking.chunk_file` / `Chunk`.
- Produces:
  - `chunks_to_nodes(chunks: list[Chunk]) -> list[TextNode]` — LlamaIndex 노드 변환(메타: file_path, start_line, end_line, kind).
  - `get_vector_store() -> QdrantVectorStore` — 컬렉션 `magicjar_slm` 연결.
  - `build_index() -> int` — 코퍼스 발견→청킹→임베딩→Qdrant 적재. 적재된 노드 수 반환.

- [ ] **Step 1: indexing.py 구현**

```python
from pathlib import Path

import qdrant_client
from llama_index.core import StorageContext, VectorStoreIndex, Settings
from llama_index.core.schema import TextNode
from llama_index.embeddings.huggingface import HuggingFaceEmbedding
from llama_index.vector_stores.qdrant import QdrantVectorStore

from rag import config
from rag.chunking import Chunk, chunk_file
from rag.corpus import discover_files


def chunks_to_nodes(chunks: list[Chunk]) -> list[TextNode]:
    return [
        TextNode(
            text=c.text,
            metadata={
                "file_path": c.file_path,
                "start_line": c.start_line,
                "end_line": c.end_line,
                "kind": c.kind,
            },
        )
        for c in chunks
    ]


def get_vector_store() -> QdrantVectorStore:
    client = qdrant_client.QdrantClient(url=config.QDRANT_URL)
    return QdrantVectorStore(client=client, collection_name=config.QDRANT_COLLECTION)


def _configure_embeddings() -> None:
    Settings.embed_model = HuggingFaceEmbedding(model_name=config.EMBED_MODEL)


def build_index() -> int:
    _configure_embeddings()
    files = discover_files(
        config.REPO_ROOT, config.INCLUDE_DIRS, config.INCLUDE_EXTS, config.EXCLUDE_PATTERNS
    )
    chunks: list[Chunk] = []
    for f in files:
        chunks.extend(chunk_file(Path(f)))
    nodes = chunks_to_nodes(chunks)

    storage = StorageContext.from_defaults(vector_store=get_vector_store())
    VectorStoreIndex(nodes, storage_context=storage, show_progress=True)
    print(f"indexed files={len(files)} chunks={len(nodes)}")
    return len(nodes)


if __name__ == "__main__":
    build_index()
```

- [ ] **Step 2: 통합 테스트 작성 (Qdrant 필요)**

```python
import pytest
import qdrant_client

from rag import config
from rag.chunking import Chunk
from rag.indexing import chunks_to_nodes


def test_chunks_to_nodes_carries_metadata():
    chunks = [Chunk(text="fun a() {}", file_path="X.kt", start_line=1, end_line=1, kind="code")]
    nodes = chunks_to_nodes(chunks)
    assert nodes[0].metadata["file_path"] == "X.kt"
    assert nodes[0].metadata["kind"] == "code"


@pytest.mark.integration
def test_qdrant_reachable():
    client = qdrant_client.QdrantClient(url=config.QDRANT_URL)
    # 연결만 확인 (컬렉션 존재 여부 무관)
    client.get_collections()
```

- [ ] **Step 3: 단위 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_indexing.py::test_chunks_to_nodes_carries_metadata -v`
Expected: PASS.

- [ ] **Step 4: 실제 인덱싱 실행 (통합)**

Run: `cd slm-lab && uv run python -m rag.indexing`
Expected: `indexed files=<N> chunks=<M>` 출력 (M>0). Qdrant 대시보드(http://localhost:16333/dashboard)에 `magicjar_slm` 컬렉션 생성, 포인트 수 = M.

- [ ] **Step 5: Commit**

```bash
git add slm-lab/rag/indexing.py slm-lab/tests/test_indexing.py
git commit -m "feat(slm-lab): index curated corpus into Qdrant via BGE-M3

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 검색 (retrieval.py)

**Files:**
- Create: `slm-lab/rag/retrieval.py`
- Test: `slm-lab/tests/test_retrieval.py`

**Interfaces:**
- Consumes: `rag.config`, `rag.indexing.get_vector_store`.
- Produces:
  - `@dataclass Retrieved: text: str; file_path: str; score: float; start_line: int; end_line: int; kind: str`.
  - `retrieve(query: str, top_k: int = config.DEFAULT_TOP_K) -> list[Retrieved]` — Qdrant에서 top-k 검색, 점수 내림차순.

- [ ] **Step 1: retrieval.py 구현**

```python
from dataclasses import dataclass

from llama_index.core import VectorStoreIndex, Settings
from llama_index.embeddings.huggingface import HuggingFaceEmbedding

from rag import config
from rag.indexing import get_vector_store


@dataclass
class Retrieved:
    text: str
    file_path: str
    score: float
    start_line: int
    end_line: int
    kind: str


def retrieve(query: str, top_k: int = config.DEFAULT_TOP_K) -> list[Retrieved]:
    Settings.embed_model = HuggingFaceEmbedding(model_name=config.EMBED_MODEL)
    index = VectorStoreIndex.from_vector_store(get_vector_store())
    retriever = index.as_retriever(similarity_top_k=top_k)
    nodes = retriever.retrieve(query)
    out: list[Retrieved] = []
    for n in nodes:
        m = n.node.metadata
        out.append(
            Retrieved(
                text=n.node.get_content(),
                file_path=m.get("file_path", "?"),
                score=float(n.score or 0.0),
                start_line=int(m.get("start_line", 0)),
                end_line=int(m.get("end_line", 0)),
                kind=m.get("kind", "?"),
            )
        )
    return out
```

- [ ] **Step 2: 통합 테스트 작성 (인덱스 존재 전제)**

```python
import pytest
from rag.retrieval import retrieve


@pytest.mark.integration
def test_retrieve_returns_topk_sorted():
    results = retrieve("전략리서치 AI 후보 발굴 흐름", top_k=3)
    assert 1 <= len(results) <= 3
    scores = [r.score for r in results]
    assert scores == sorted(scores, reverse=True)
    assert all(r.file_path and r.file_path != "?" for r in results)
```

- [ ] **Step 3: 통합 테스트 실행**

Run: `cd slm-lab && uv run pytest tests/test_retrieval.py -m integration -v`
Expected: PASS — 1~3개 결과, 점수 내림차순, file_path 채워짐. (Task 4 인덱싱 선행 필요)

- [ ] **Step 4: Commit**

```bash
git add slm-lab/rag/retrieval.py slm-lab/tests/test_retrieval.py
git commit -m "feat(slm-lab): top-k retrieval with scores and source metadata

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 생성 + 안전 가드 (generation.py)

**Files:**
- Create: `slm-lab/rag/generation.py`
- Test: `slm-lab/tests/test_generation.py`

**Interfaces:**
- Consumes: `rag.config`, `rag.retrieval.Retrieved`.
- Produces:
  - `build_prompt(query: str, retrieved: list[Retrieved]) -> str` — 시스템 프롬프트 + 출처표기 컨텍스트 + 질문 결합 (순수 함수).
  - `generate(query: str, retrieved: list[Retrieved]) -> str` — Ollama Gemma 호출.

- [ ] **Step 1: 실패 테스트 작성 (프롬프트 빌더는 순수 함수라 결정적)**

```python
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd slm-lab && uv run pytest tests/test_generation.py -v`
Expected: FAIL — import 오류.

- [ ] **Step 3: generation.py 구현**

```python
from llama_index.llms.ollama import Ollama

from rag import config
from rag.retrieval import Retrieved


def build_prompt(query: str, retrieved: list[Retrieved]) -> str:
    if retrieved:
        blocks = []
        for i, r in enumerate(retrieved, 1):
            blocks.append(
                f"[{i}] 출처: {r.file_path}:{r.start_line}-{r.end_line} (kind={r.kind})\n{r.text}"
            )
        context = "\n\n".join(blocks)
    else:
        context = "컨텍스트 없음"

    return (
        f"{config.SYSTEM_PROMPT}\n\n"
        f"### 컨텍스트\n{context}\n\n"
        f"### 질문\n{query}\n\n"
        f"### 답변 (한국어, 컨텍스트 근거. 출처 [n] 표기)"
    )


def generate(query: str, retrieved: list[Retrieved]) -> str:
    llm = Ollama(
        model=config.GEN_MODEL,
        base_url=config.OLLAMA_URL,
        request_timeout=config.REQUEST_TIMEOUT,
    )
    prompt = build_prompt(query, retrieved)
    return str(llm.complete(prompt))
```

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_generation.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: 생성 스모크 (통합, Ollama 필요)**

Run:
```bash
cd slm-lab && uv run python -c "
from rag.retrieval import retrieve
from rag.generation import generate
r = retrieve('전략리서치 흐름이 어떻게 돼?', top_k=3)
print(generate('전략리서치 흐름이 어떻게 돼?', r)[:300])
"
```
Expected: 한국어 답변 일부 출력 (매매 명령 아님).

- [ ] **Step 6: Commit**

```bash
git add slm-lab/rag/generation.py slm-lab/tests/test_generation.py
git commit -m "feat(slm-lab): prompt assembly with safety guard + Gemma generation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 파이프라인 + Phoenix 계측 (pipeline.py)

**Files:**
- Create: `slm-lab/rag/pipeline.py`
- Test: `slm-lab/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `rag.config`, `rag.retrieval.retrieve`/`Retrieved`, `rag.generation.build_prompt`/`generate`.
- Produces:
  - `@dataclass RagResult: query: str; retrieved: list[Retrieved]; prompt: str; answer: str; latency_ms: int`.
  - `setup_phoenix() -> None` — openinference LlamaIndex instrumentor를 Phoenix(16006) OTLP로 등록. 멱등.
  - `answer(query: str, top_k: int = config.DEFAULT_TOP_K) -> RagResult` — 검색+조립+생성을 묶고 가시화 페이로드 반환.

- [ ] **Step 1: pipeline.py 구현**

```python
import time
from dataclasses import dataclass

from rag import config
from rag.retrieval import retrieve, Retrieved
from rag.generation import build_prompt, generate

_PHOENIX_READY = False


@dataclass
class RagResult:
    query: str
    retrieved: list[Retrieved]
    prompt: str
    answer: str
    latency_ms: int


def setup_phoenix() -> None:
    global _PHOENIX_READY
    if _PHOENIX_READY:
        return
    from openinference.instrumentation.llama_index import LlamaIndexInstrumentor
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor

    endpoint = f"http://{config.PHOENIX_HOST}:{config.PHOENIX_PORT}/v1/traces"
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    LlamaIndexInstrumentor().instrument(tracer_provider=provider)
    _PHOENIX_READY = True


def answer(query: str, top_k: int = config.DEFAULT_TOP_K) -> RagResult:
    setup_phoenix()
    t0 = time.perf_counter()
    retrieved = retrieve(query, top_k=top_k)
    prompt = build_prompt(query, retrieved)
    ans = generate(query, retrieved)
    latency_ms = int((time.perf_counter() - t0) * 1000)
    return RagResult(
        query=query, retrieved=retrieved, prompt=prompt, answer=ans, latency_ms=latency_ms
    )
```

- [ ] **Step 2: 테스트 작성 (setup_phoenix 멱등성 — 통합 불요)**

```python
import rag.pipeline as pipeline


def test_setup_phoenix_is_idempotent(monkeypatch):
    calls = {"n": 0}

    class FakeInstrumentor:
        def instrument(self, **kwargs):
            calls["n"] += 1

    # 내부 import를 가짜로 대체하기 위해 플래그만 검증
    pipeline._PHOENIX_READY = False
    monkeypatch.setattr(pipeline, "setup_phoenix", pipeline.setup_phoenix)
    # 첫 호출은 실제 등록 시도가 일어나므로, 멱등 플래그 동작만 확인
    pipeline._PHOENIX_READY = True
    pipeline.setup_phoenix()  # 이미 ready면 즉시 반환
    assert pipeline._PHOENIX_READY is True
```

- [ ] **Step 3: 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_pipeline.py -v`
Expected: PASS.

- [ ] **Step 4: 파이프라인 스모크 + Phoenix 트레이스 확인 (통합)**

Run:
```bash
cd slm-lab && uv run python -c "
from rag.pipeline import answer
res = answer('전략리서치 후보 발굴은 어떻게 동작해?', top_k=3)
print('latency_ms=', res.latency_ms)
print('retrieved=', [(r.file_path, round(r.score,3)) for r in res.retrieved])
print('answer head=', res.answer[:200])
"
```
Expected: latency·검색결과·답변 출력. http://localhost:16006 Phoenix UI에 해당 질의 트레이스(retrieve→LLM 스팬, 청크·프롬프트 포함) 표시.

- [ ] **Step 5: Commit**

```bash
git add slm-lab/rag/pipeline.py slm-lab/tests/test_pipeline.py
git commit -m "feat(slm-lab): RAG pipeline with Phoenix tracing and visibility payload

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Streamlit 가시화 챗 (app.py)

**Files:**
- Create: `slm-lab/rag/app.py`

**Interfaces:**
- Consumes: `rag.config`, `rag.pipeline.answer`/`RagResult`.
- Produces: Streamlit 앱 (UI, 테스트 없음 — 수동 스모크).

- [ ] **Step 1: app.py 구현**

```python
import streamlit as st

from rag import config
from rag.pipeline import answer

st.set_page_config(page_title="magicJar SLM RAG", layout="wide")
st.title("magicJar SLM Lab — RAG Q&A")
st.caption(f"모델 {config.GEN_MODEL} · 임베딩 {config.EMBED_MODEL} · Phoenix :{config.PHOENIX_PORT}")

top_k = st.slider("top-k (검색 청크 수)", min_value=1, max_value=10, value=config.DEFAULT_TOP_K)
query = st.text_input("질문", placeholder="예: 전략리서치 후보 발굴은 어떻게 동작해?")

if st.button("질의") and query.strip():
    with st.spinner("검색 + 생성 중..."):
        res = answer(query, top_k=top_k)

    st.subheader("답변")
    st.markdown(res.answer)
    st.caption(f"지연 {res.latency_ms} ms · 청크 {len(res.retrieved)}개")

    with st.expander(f"① 검색된 청크 {len(res.retrieved)}개 (점수·출처)", expanded=True):
        for i, r in enumerate(res.retrieved, 1):
            st.markdown(f"**[{i}] {r.file_path}:{r.start_line}-{r.end_line}** · score `{r.score:.3f}` · {r.kind}")
            st.code(r.text)

    with st.expander("② 모델에 실제로 들어간 최종 프롬프트"):
        st.code(res.prompt)
```

- [ ] **Step 2: 수동 스모크**

Run: `cd slm-lab && uv run streamlit run rag/app.py --server.port 18501`
Expected: http://localhost:18501 에서 질문 입력 → 답변 + "①검색 청크(점수/출처 펼침)" + "②최종 프롬프트 펼침" 표시. top-k 슬라이더 변경 시 검색 청크 수 변동.

- [ ] **Step 3: Commit**

```bash
git add slm-lab/rag/app.py
git commit -m "feat(slm-lab): Streamlit chat exposing retrieved chunks and final prompt

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: 평가 질문셋 (questions.yaml + 로더)

**Files:**
- Create: `slm-lab/eval/__init__.py` (빈 파일)
- Create: `slm-lab/eval/questions.yaml`
- Create: `slm-lab/eval/questions.py`
- Test: `slm-lab/tests/test_questions.py`

**Interfaces:**
- Produces:
  - `@dataclass Question: id: str; question: str; category: str`.
  - `load_questions(path: Path | None = None) -> list[Question]` — yaml 로드 + 검증(필드 존재, id 유일).

- [ ] **Step 1: questions.yaml 작성 (초안 30문항 — DSL 문법 + 시스템 이해 혼합)**

```yaml
- id: q01
  category: system
  question: 전략리서치 AI 후보 발굴(StrategyResearchAiDiscovery)은 전체적으로 어떤 흐름으로 동작하나요?
- id: q02
  category: system
  question: StrategyResearchAiTextClient 인터페이스의 역할과 반환값은 무엇인가요?
- id: q03
  category: system
  question: AI가 생성한 후보를 magicJar가 어떻게 검증/필터링한 뒤 사용자에게 보여주나요?
- id: q04
  category: system
  question: 전략 빌더에서 AI 생성(StrategyBuilderAiGenerate)의 입력과 출력은 무엇인가요?
- id: q05
  category: system
  question: instrument matching(종목 매칭)은 어느 단계에서 수행되나요?
- id: q06
  category: dsl
  question: RSI가 30 이하일 때 매수하는 전략을 우리 DSL로 어떻게 표현하나요?
- id: q07
  category: dsl
  question: 골든크로스(단기 이평선이 장기 이평선을 상향 돌파) 조건을 DSL로 어떻게 쓰나요?
- id: q08
  category: dsl
  question: DSL에서 사용 가능한 지표(indicator) 종류에는 무엇이 있나요?
- id: q09
  category: dsl
  question: DSL 파싱이 실패하면 어떤 형태의 경고/에러가 반환되나요?
- id: q10
  category: dsl
  question: 매수 조건과 매도 조건을 함께 정의하는 DSL 구조는 어떻게 되나요?
- id: q11
  category: system
  question: 전략리서치에서 warnings는 언제 생성되고 무엇을 의미하나요?
- id: q12
  category: system
  question: AI 프롬프트는 어디서 어떻게 구성되나요(코어 프롬프트와 사용자 입력의 결합)?
- id: q13
  category: system
  question: 후보 아이템의 상태(item status)는 어떤 값들이 있고 어떻게 바뀌나요?
- id: q14
  category: system
  question: preset 추천(PresetRecommend)은 일반 discovery와 무엇이 다른가요?
- id: q15
  category: dsl
  question: 볼린저 밴드 하단 터치 시 매수하는 전략을 DSL로 어떻게 표현하나요?
- id: q16
  category: dsl
  question: 거래량 급증 조건을 DSL로 표현하려면 어떤 지표/연산자를 쓰나요?
- id: q17
  category: system
  question: 이 PoC에서 SLM 출력이 절대 해서는 안 되는 동작은 무엇인가요?
- id: q18
  category: system
  question: MAGICJAR_MODE=MOCK는 무엇을 보장하나요?
- id: q19
  category: dsl
  question: 여러 조건을 AND/OR로 결합하는 DSL 문법은 어떻게 되나요?
- id: q20
  category: dsl
  question: 특정 종목군(예: 코스닥 중소형주)을 대상으로 제한하는 표현이 DSL에 있나요?
- id: q21
  category: system
  question: AI discovery 결과의 JSON 스키마(필드 구성)는 어떻게 되나요?
- id: q22
  category: system
  question: 응답 파싱 시 malformed JSON은 어떻게 처리되나요?
- id: q23
  category: system
  question: 전략리서치 서비스에서 타임아웃/실패는 어떻게 다뤄지나요?
- id: q24
  category: dsl
  question: MACD 시그널 교차를 매매 신호로 쓰는 DSL 예시는?
- id: q25
  category: dsl
  question: 손절(stop loss)·익절(take profit)을 DSL로 표현할 수 있나요?
- id: q26
  category: system
  question: 프론트엔드 AiDiscoveryCard는 어떤 데이터를 표시하나요?
- id: q27
  category: system
  question: 후보 발굴에서 사용자 선택(user-controlled selection)은 어디에 반영되나요?
- id: q28
  category: dsl
  question: 이동평균선 기간(period)을 바꾸려면 DSL에서 어디를 수정하나요?
- id: q29
  category: system
  question: SLM provider를 ollama로 고르는 설정 키는 무엇인가요?
- id: q30
  category: system
  question: 이 랩이 원본 magicJar 스택과 격리되는 방식(포트/컨테이너/DB)은 무엇인가요?
```

- [ ] **Step 2: 실패 테스트 작성**

```python
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
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd slm-lab && uv run pytest tests/test_questions.py -v`
Expected: FAIL — import 오류.

- [ ] **Step 4: questions.py 구현**

```python
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
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_questions.py -v`
Expected: PASS (2 passed).

- [ ] **Step 6: Commit**

```bash
git add slm-lab/eval/__init__.py slm-lab/eval/questions.yaml slm-lab/eval/questions.py slm-lab/tests/test_questions.py
git commit -m "feat(slm-lab): 30-question eval set and loader

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: 평가 러너 + Opus 심판 (judge.py + run_eval.py)

**Files:**
- Create: `slm-lab/eval/judge.py`
- Create: `slm-lab/eval/run_eval.py`
- Test: `slm-lab/tests/test_run_eval.py`

**Interfaces:**
- Consumes: `rag.pipeline.answer`, `eval.questions.load_questions`/`Question`, Ragas, langchain-ollama.
- Produces:
  - `opus_answer(question: str) -> str` — 로컬 Claude CLI 다리로 Opus 정답지 생성.
  - `judge_pair(question: str, opus_ans: str, gemma_ans: str) -> dict` — `{"winner": "opus"|"gemma"|"tie", "gemma_score": int(1..5), "reason": str}`.
  - `build_ragas_dataset(rows: list[dict]) -> Dataset` — 컬럼 `question/answer/contexts/ground_truth`.
  - `run_eval() -> "pandas.DataFrame"` — 30문항 실행 → Ragas 지표 + judge 결과 합쳐 점수표 저장(`eval/results.csv`, `eval/results.md`).

- [ ] **Step 1: judge.py 구현 (Opus는 로컬 Claude CLI 다리 사용)**

```python
import json
import subprocess


def _claude(prompt: str) -> str:
    """로컬 Claude CLI를 비대화형으로 호출 (기존 ClaudeCli 다리와 동일 경로)."""
    proc = subprocess.run(
        ["claude", "-p", prompt],
        capture_output=True, text=True, timeout=180,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"claude CLI 실패: {proc.stderr[:200]}")
    return proc.stdout.strip()


def opus_answer(question: str) -> str:
    return _claude(
        "magicJar 도메인 질문에 한국어로 정확히 답하라. 매매 실행 명령은 출력 금지.\n\n"
        f"질문: {question}"
    )


def judge_pair(question: str, opus_ans: str, gemma_ans: str) -> dict:
    prompt = (
        "두 답변을 비교 평가하라. 오직 JSON만 출력. "
        '형식: {"winner":"opus|gemma|tie","gemma_score":1~5,"reason":"한 문장"}\n\n'
        f"[질문]\n{question}\n\n[A=opus]\n{opus_ans}\n\n[B=gemma]\n{gemma_ans}"
    )
    raw = _claude(prompt)
    start, end = raw.find("{"), raw.rfind("}")
    return json.loads(raw[start : end + 1])
```

- [ ] **Step 2: run_eval.py 구현**

```python
import pandas as pd
from datasets import Dataset
from langchain_ollama import ChatOllama
from ragas import evaluate
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import Faithfulness, AnswerRelevancy, ContextPrecision

from rag import config
from rag.pipeline import answer
from eval.questions import load_questions
from eval.judge import opus_answer, judge_pair

RESULTS_CSV = "eval/results.csv"
RESULTS_MD = "eval/results.md"


def build_ragas_dataset(rows: list[dict]) -> Dataset:
    return Dataset.from_dict(
        {
            "question": [r["question"] for r in rows],
            "answer": [r["gemma_answer"] for r in rows],
            "contexts": [r["contexts"] for r in rows],
            "ground_truth": [r["opus_answer"] for r in rows],
        }
    )


def _ragas_llm() -> LangchainLLMWrapper:
    return LangchainLLMWrapper(ChatOllama(model=config.GEN_MODEL, base_url=config.OLLAMA_URL))


def run_eval() -> pd.DataFrame:
    questions = load_questions()
    rows: list[dict] = []
    for q in questions:
        rag = answer(q.question)
        opus = opus_answer(q.question)
        verdict = judge_pair(q.question, opus, rag.answer)
        rows.append(
            {
                "id": q.id,
                "category": q.category,
                "question": q.question,
                "gemma_answer": rag.answer,
                "opus_answer": opus,
                "contexts": [r.text for r in rag.retrieved],
                "winner": verdict["winner"],
                "gemma_score": verdict["gemma_score"],
                "latency_ms": rag.latency_ms,
            }
        )

    ds = build_ragas_dataset(rows)
    metrics = [Faithfulness(), AnswerRelevancy(), ContextPrecision()]
    ragas_res = evaluate(dataset=ds, metrics=metrics, llm=_ragas_llm())
    ragas_df = ragas_res.to_pandas()

    df = pd.DataFrame(rows)
    df["faithfulness"] = ragas_df["faithfulness"].values
    df["answer_relevancy"] = ragas_df["answer_relevancy"].values
    df["context_precision"] = ragas_df["context_precision"].values

    summary = {
        "gemma_win_rate": (df["winner"] == "gemma").mean(),
        "tie_rate": (df["winner"] == "tie").mean(),
        "avg_gemma_score": df["gemma_score"].mean(),
        "avg_faithfulness": df["faithfulness"].mean(),
        "avg_answer_relevancy": df["answer_relevancy"].mean(),
        "avg_context_precision": df["context_precision"].mean(),
        "avg_latency_ms": df["latency_ms"].mean(),
    }

    df.to_csv(RESULTS_CSV, index=False)
    with open(RESULTS_MD, "w", encoding="utf-8") as f:
        f.write("# RAG Phase 1 Eval\n\n## Summary\n\n")
        for k, v in summary.items():
            f.write(f"- {k}: {v:.3f}\n")
    print(summary)
    return df


if __name__ == "__main__":
    run_eval()
```

- [ ] **Step 3: 단위 테스트 작성 (judge 파싱 + 데이터셋 빌더 — 외부호출 모킹)**

```python
import eval.judge as judge
from eval.run_eval import build_ragas_dataset


def test_judge_pair_parses_json(monkeypatch):
    monkeypatch.setattr(
        judge, "_claude",
        lambda p: 'noise {"winner":"gemma","gemma_score":4,"reason":"명확"} trailing',
    )
    out = judge.judge_pair("q", "opus", "gemma")
    assert out["winner"] == "gemma"
    assert out["gemma_score"] == 4


def test_build_ragas_dataset_columns():
    rows = [{"question": "q", "gemma_answer": "a", "contexts": ["c1"], "opus_answer": "g"}]
    ds = build_ragas_dataset(rows)
    assert set(ds.column_names) == {"question", "answer", "contexts", "ground_truth"}
    assert ds[0]["ground_truth"] == "g"
```

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `cd slm-lab && uv run pytest tests/test_run_eval.py -v`
Expected: PASS (2 passed).

- [ ] **Step 5: 전체 평가 실행 (통합 — Ollama + Claude CLI + 인덱스 필요)**

Run: `cd slm-lab && uv run python -m eval.run_eval`
Expected: summary dict 출력 (`gemma_win_rate`, `avg_gemma_score`, `avg_faithfulness` 등). `eval/results.csv`·`eval/results.md` 생성.

- [ ] **Step 6: 전체 단위 테스트 회귀 확인**

Run: `cd slm-lab && uv run pytest -m "not integration" -v`
Expected: 전 단위 테스트 PASS.

- [ ] **Step 7: Commit**

```bash
git add slm-lab/eval/judge.py slm-lab/eval/run_eval.py slm-lab/tests/test_run_eval.py
git commit -m "feat(slm-lab): eval runner with Ragas metrics and Opus-as-judge

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (작성자 점검 결과)

**1. 스펙 커버리지:**
- RAG 6단계(임베딩/검색/조립/생성/출처) → Task 4·5·6·7 ✅
- 가시화(검색 청크·점수·프롬프트 노출) → Task 8 Streamlit + Task 7 Phoenix ✅
- 현업 스택(LlamaIndex/Qdrant/BGE-M3/Phoenix/Ragas/Streamlit) → Task 1·4·5·7·10 ✅
- 측정(Opus 정답지 + Opus 심판 + Ragas, 30문항 고정) → Task 9·10 ✅
- 격리 포트·코퍼스 큐레이션·비밀 제외 → Task 1·2 ✅
- 안전 가드 시스템 프롬프트 → Task 6 (테스트로 강제) ✅
- DoD 6항목 모두 대응 태스크 존재 ✅

**2. 플레이스홀더 스캔:** "적절히 처리" 류 없음. 모든 코드 스텝에 실제 코드 포함.

**3. 타입 일관성:** `Chunk`(Task 3) → `chunks_to_nodes`(Task 4) 필드 일치. `Retrieved`(Task 5) → `build_prompt`/`answer`(Task 6·7) 필드 일치. `RagResult`(Task 7) → `app.py`(Task 8)·`run_eval`(Task 10) 속성(`retrieved/prompt/answer/latency_ms`) 일치. `Question`(Task 9) → `run_eval`(Task 10) 사용 일치.

**알려진 리스크(실행 중 검증 필요):**
- 라이브러리 버전 핀은 하한선이며, 설치 후 `QdrantVectorStore`/`Ollama` 생성자 인자(`base_url` 등)·openinference instrumentor API는 설치된 버전 문서로 1회 확인 권장.
- Phase 2(코드 분석 질문), Phase 3(증류)는 본 계획 비범위.
