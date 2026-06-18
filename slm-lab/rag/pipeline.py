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


def _install_instrumentation() -> None:
    from openinference.instrumentation.llama_index import LlamaIndexInstrumentor
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor

    endpoint = f"http://{config.PHOENIX_HOST}:{config.PHOENIX_PORT}/v1/traces"
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    LlamaIndexInstrumentor().instrument(tracer_provider=provider)


def setup_phoenix() -> None:
    global _PHOENIX_READY
    if _PHOENIX_READY:
        return
    _install_instrumentation()
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
