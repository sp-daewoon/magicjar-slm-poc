import json
import shutil
import subprocess

# shutil.which 실패 시 알려진 절대 경로로 폴백 (중첩 실행 환경 대응)
_CLAUDE_BIN = shutil.which("claude") or "/Users/ak-song/.local/bin/claude"


_TIMEOUT = 300
_RETRIES = 2


def _claude(prompt: str) -> str:
    """로컬 Claude CLI를 비대화형으로 호출 (기존 ClaudeCli 다리와 동일 경로).

    동시 호출 경합 등으로 간헐적 행이 발생할 수 있어 타임아웃 시 재시도한다.
    """
    last_err: Exception | None = None
    for _ in range(_RETRIES):
        try:
            proc = subprocess.run(
                [_CLAUDE_BIN, "-p", prompt],
                capture_output=True, text=True, timeout=_TIMEOUT,
            )
            if proc.returncode != 0:
                raise RuntimeError(f"claude CLI 실패: {proc.stderr[:200]}")
            return proc.stdout.strip()
        except subprocess.TimeoutExpired as e:
            last_err = e
            continue
    raise RuntimeError(f"claude CLI 타임아웃({_TIMEOUT}s) x{_RETRIES}") from last_err


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
