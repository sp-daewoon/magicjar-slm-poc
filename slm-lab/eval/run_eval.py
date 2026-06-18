import math

import pandas as pd
from datasets import Dataset

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


def _ragas_llm():
    from langchain_ollama import ChatOllama
    from ragas.llms import LangchainLLMWrapper  # type: ignore[attr-defined]
    return LangchainLLMWrapper(ChatOllama(model=config.GEN_MODEL, base_url=config.OLLAMA_URL))


def _ragas_embeddings():
    # ragas 0.4.3의 HuggingFaceEmbeddings(새 버전)는 embed_query/embed_documents가 없어
    # AnswerRelevancy가 NaN을 리턴한다. LangchainEmbeddingsWrapper를 통해
    # langchain_community HuggingFaceEmbeddings를 감싸면 embed_query가 제공된다.
    # Phase-1 known limitation: langchain_community는 deprecated 경고가 뜨지만 동작은 정상.
    from langchain_community.embeddings import HuggingFaceEmbeddings as LcHFEmb
    from ragas.embeddings.base import LangchainEmbeddingsWrapper  # type: ignore[attr-defined]
    return LangchainEmbeddingsWrapper(LcHFEmb(model_name=config.EMBED_MODEL))


def run_eval(limit: int | None = None) -> pd.DataFrame:
    questions = load_questions()
    if limit is not None:
        questions = questions[:limit]

    # --- 1단계: Opus-as-judge 평가 (ragas 없이도 항상 완료해야 함) ---
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

    df = pd.DataFrame(rows)

    # --- 2단계: ragas 메트릭 (실패 시 NaN으로 폴백; 코어 평가 결과는 보존) ---
    # ragas 0.4.3은 VertexAI 임포트 try/except를 내장하고 있어 대부분 정상 임포트됨.
    # 하지만 버전 불일치·오프라인 환경 등 예기치 않은 오류가 생겨도 런너가 죽지 않도록
    # 전체 블록을 try/except로 보호한다.
    nan = float("nan")
    try:
        from ragas import evaluate
        from ragas.metrics import Faithfulness, AnswerRelevancy, ContextPrecision  # type: ignore[attr-defined]

        ds = build_ragas_dataset(rows)
        metrics = [Faithfulness(), AnswerRelevancy(), ContextPrecision()]
        ragas_res = evaluate(
            dataset=ds,
            metrics=metrics,
            llm=_ragas_llm(),
            embeddings=_ragas_embeddings(),
        )
        ragas_df = ragas_res.to_pandas()
        df["faithfulness"] = ragas_df["faithfulness"].values
        df["answer_relevancy"] = ragas_df["answer_relevancy"].values
        df["context_precision"] = ragas_df["context_precision"].values
    except Exception as e:
        print(f"[eval] ragas metrics skipped: {e}")
        df["faithfulness"] = nan
        df["answer_relevancy"] = nan
        df["context_precision"] = nan

    # --- 3단계: 결과 저장 ---
    def _safe_mean(series: "pd.Series") -> float:
        vals = series.dropna()
        return float(vals.mean()) if len(vals) > 0 else nan

    summary = {
        "gemma_win_rate": (df["winner"] == "gemma").mean(),
        "tie_rate": (df["winner"] == "tie").mean(),
        "avg_gemma_score": df["gemma_score"].mean(),
        "avg_faithfulness": _safe_mean(df["faithfulness"]),
        "avg_answer_relevancy": _safe_mean(df["answer_relevancy"]),
        "avg_context_precision": _safe_mean(df["context_precision"]),
        "avg_latency_ms": df["latency_ms"].mean(),
    }

    df.to_csv(RESULTS_CSV, index=False)
    with open(RESULTS_MD, "w", encoding="utf-8") as f:
        f.write("# RAG Phase 1 Eval\n\n## Summary\n\n")
        for k, v in summary.items():
            v_str = f"{v:.3f}" if not (isinstance(v, float) and math.isnan(v)) else "NaN"
            f.write(f"- {k}: {v_str}\n")
    print(summary)
    return df


if __name__ == "__main__":
    run_eval()
