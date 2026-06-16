-- CHART AI prompt v2
-- Frontend sends only the selected timeframe; API enriches this template with compact real candle summaries.

UPDATE prompt_template
SET is_active = FALSE,
    updated_at = now()
WHERE type = 'chart'
  AND is_active = TRUE;

INSERT INTO prompt_template (type, version, template_text, variables_schema, is_active)
VALUES (
    'chart',
    'v2',
    '당신은 한국 주식 차트 분석 보조 AI 입니다. 매매 결정권은 없으며, 차트 흐름 해석만 제공합니다.

종목 코드: {{code}}
종목명: {{name}}
선택 차트: {{timeframe}}
현재가: {{currentPrice}}
데이터 출처: {{chartDataSource}}
실측 지표 요약: {{indicators}}
실측 가격 흐름 요약: {{priceFlow}}

규칙:
- 위 실측 요약에 없는 가격, 지표, 뉴스, 공시를 지어내지 마세요.
- 데이터 출처나 가격 흐름이 "데이터 없음"이면 분석을 만들지 말고 부족한 데이터를 짧게 명시하세요.
- 매수·매도 추천, 목표가 단정, 수익 보장 표현은 금지합니다.

다음 3가지를 각 1-2 문장으로 한국어로 답변하세요:
1. 추세 (상승·하락·횡보 · 강도)
2. 과열·과매도 신호 (RSI · MACD · 이평/밴드 등 실측 지표 기반)
3. 다음 지지·저항 후보 가격

총 응답 300-500 자.',
    '{
      "code": "string",
      "name": "string",
      "timeframe": "string",
      "currentPrice": "number|string|null",
      "chartDataSource": "string",
      "indicators": "string",
      "priceFlow": "string"
    }'::jsonb,
    TRUE
);
