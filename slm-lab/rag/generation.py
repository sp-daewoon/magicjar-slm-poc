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


def generate(prompt: str) -> str:
    llm = Ollama(
        model=config.GEN_MODEL,
        base_url=config.OLLAMA_URL,
        request_timeout=config.REQUEST_TIMEOUT,
    )
    return str(llm.complete(prompt))
