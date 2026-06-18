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
    from ragas.llms import LangchainLLMWrapper
    return LangchainLLMWrapper(ChatOllama(model=config.GEN_MODEL, base_url=config.OLLAMA_URL))


def _ragas_embeddings():
    from ragas.embeddings import HuggingFaceEmbeddings
    return HuggingFaceEmbeddings(model=config.EMBED_MODEL)


def run_eval(limit: int | None = None) -> pd.DataFrame:
    from ragas import evaluate
    from ragas.metrics import Faithfulness, AnswerRelevancy, ContextPrecision

    questions = load_questions()
    if limit is not None:
        questions = questions[:limit]
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
    ragas_res = evaluate(dataset=ds, metrics=metrics, llm=_ragas_llm(), embeddings=_ragas_embeddings())
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
