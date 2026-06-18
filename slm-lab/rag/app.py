import streamlit as st

from rag import config
from rag.pipeline import answer

st.set_page_config(page_title="magicJar SLM RAG", layout="wide")
st.title("magicJar SLM Lab — RAG Q&A")
st.caption(f"모델 {config.GEN_MODEL} · 임베딩 {config.EMBED_MODEL} · Phoenix :{config.PHOENIX_PORT}")

top_k = st.slider("top-k (검색 청크 수)", min_value=1, max_value=10, value=config.DEFAULT_TOP_K)
query = st.text_input("질문", placeholder="예: 전략리서치 후보 발굴은 어떻게 동작해?")

if st.button("질의") and query.strip():
    with st.spinner("검색 + 생성 중..."):
        res = answer(query, top_k=top_k)

    st.subheader("답변")
    st.markdown(res.answer)
    st.caption(f"지연 {res.latency_ms} ms · 청크 {len(res.retrieved)}개")

    with st.expander(f"① 검색된 청크 {len(res.retrieved)}개 (점수·출처)", expanded=True):
        for i, r in enumerate(res.retrieved, 1):
            st.markdown(f"**[{i}] {r.file_path}:{r.start_line}-{r.end_line}** · score `{r.score:.3f}` · {r.kind}")
            st.code(r.text)

    with st.expander("② 모델에 실제로 들어간 최종 프롬프트"):
        st.code(res.prompt)
