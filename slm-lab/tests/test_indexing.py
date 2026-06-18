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
