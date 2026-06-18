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
