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
    Settings.embed_model = HuggingFaceEmbedding(
        model_name=config.EMBED_MODEL,
        max_length=512,
        embed_batch_size=4,
    )


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
