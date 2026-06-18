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
