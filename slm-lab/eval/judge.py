import json
import shutil
import subprocess

# shutil.which 실패 시 알려진 절대 경로로 폴백 (중첩 실행 환경 대응)
_CLAUDE_BIN = shutil.which("claude") or "/Users/ak-song/.local/bin/claude"


def _claude(prompt: str) -> str:
    """로컬 Claude CLI를 비대화형으로 호출 (기존 ClaudeCli 다리와 동일 경로)."""
    proc = subprocess.run(
        [_CLAUDE_BIN, "-p", prompt],
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
