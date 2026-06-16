# KIS REST curl cookbook

KIS OpenAPI를 터미널 curl로 직접 호출하는 재사용 모음. 시세·캔들 결함을 앱 없이 raw로 검증할 때 사용.

> **💡 더 편한 방법 (권장)**: 키를 매번 넣기 번거로우면 IntelliJ/VS Code **HTTP Client**를 쓰세요 → [`kis.http`](kis.http). `http-client.private.env.json`(gitignore)에 키 한 번만 넣고, "토큰 발급" ▶ 실행하면 토큰이 자동 캡처되어 모든 요청이 그대로 동작합니다. 아래 curl 모음은 셸/CI/서버 환경용 대안입니다.

> **시크릿 안전**: appkey/appsecret/token은 **이 파일에 절대 적지 말 것**. 아래처럼 셸 환경변수로만 주입한다(히스토리·파일에 평문 남기지 않기). 키는 KIS 개발자센터 "실전(조회)" 키 — 시세·캔들 조회는 실전 키 정합(`feedback_kis_key_realonly_for_data`).

---

## 0. 환경변수 셋업 (실전/조회 키)

```bash
export KIS_DOMAIN="https://openapi.koreainvestment.com:9443"   # 실전. 모의=https://openapivts.koreainvestment.com:29443
export KIS_APPKEY="<실전 조회 appkey>"
export KIS_APPSECRET="<실전 조회 appsecret>"
```

## 1. 접근토큰 발급 (`/oauth2/tokenP`)

토큰은 ~24h 유효. 발급 후 `KIS_TOKEN`에 저장해 재사용(과발급 시 KIS가 1분당 1회 제한).

```bash
export KIS_TOKEN=$(curl -s -X POST "$KIS_DOMAIN/oauth2/tokenP" \
  -H "content-type: application/json" \
  -d "{\"grant_type\":\"client_credentials\",\"appkey\":\"$KIS_APPKEY\",\"appsecret\":\"$KIS_APPSECRET\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
echo "token len=${#KIS_TOKEN}"   # 0이면 키/제한 확인
```

공통 헤더(모든 조회 API 동일):
- `authorization: Bearer $KIS_TOKEN`
- `appkey: $KIS_APPKEY` · `appsecret: $KIS_APPSECRET`
- `tr_id: <API별>` · `custtype: P`(개인)

---

## 2. 국내주식 기간별 일봉 — `FHKST03010100` ★ 일봉 갭 검증용

`/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice`
- 응답 `output2[]` = 일봉 배열(`stck_bsop_date`·`stck_clpr`·`stck_oprc`·`stck_hgpr`·`stck_lwpr`·`acml_vol`), 최신이 `[0]`.
- 시장구분 `FID_COND_MRKT_DIV_CODE`: **`J`=KRX**, `NX`=NXT, `UN`=통합.

### 2-A. 036930 갭 검증 — J 채널에 2/11 이후 데이터가 오나?

```bash
curl -s -G "$KIS_DOMAIN/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice" \
  -H "authorization: Bearer $KIS_TOKEN" -H "appkey: $KIS_APPKEY" -H "appsecret: $KIS_APPSECRET" \
  -H "tr_id: FHKST03010100" -H "custtype: P" \
  --data-urlencode "FID_COND_MRKT_DIV_CODE=J" \
  --data-urlencode "FID_INPUT_ISCD=036930" \
  --data-urlencode "FID_INPUT_DATE_1=20260212" \
  --data-urlencode "FID_INPUT_DATE_2=20260609" \
  --data-urlencode "FID_PERIOD_DIV_CODE=D" \
  --data-urlencode "FID_ORG_ADJ_PRC=0" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); o=d.get("output2") or []; print("rt_cd",d.get("rt_cd"),d.get("msg1")); print("rows",len([r for r in o if r.get("stck_bsop_date")])); print("newest",o[0].get("stck_bsop_date") if o else None,"oldest",o[-1].get("stck_bsop_date") if o else None)'
```

**판정:**
- `rows>0` + `newest`가 2026-06-09 근처 → **KIS J 채널엔 데이터 있음** = 우리 수집 경로 결함(채널 fallback 의심) → 코드 fix 대상.
- `rows=0` 또는 `newest=20260211` → **KIS가 J에도 2/11 이후를 안 줌** = 데이터 부재(상장/NXT 이벤트) → 2-C로 이벤트 확인.

### 2-B. 같은 호출을 NX(NXT)로 — J vs NX 비교

```bash
# 위 2-A에서 FID_COND_MRKT_DIV_CODE=J → NX 로만 바꿔 재실행.
#   J만 최신 있고 NX는 2/11까지면 → NXT 채널 단절(우리가 NX만 보는 게 원인).
```

### 2-C. 나머지 stale 종목 일괄 (047040·098460·138040·455180)

```bash
for CODE in 047040 098460 138040 455180; do
  echo "=== $CODE ==="
  curl -s -G "$KIS_DOMAIN/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice" \
    -H "authorization: Bearer $KIS_TOKEN" -H "appkey: $KIS_APPKEY" -H "appsecret: $KIS_APPSECRET" \
    -H "tr_id: FHKST03010100" -H "custtype: P" \
    --data-urlencode "FID_COND_MRKT_DIV_CODE=J" --data-urlencode "FID_INPUT_ISCD=$CODE" \
    --data-urlencode "FID_INPUT_DATE_1=20260212" --data-urlencode "FID_INPUT_DATE_2=20260609" \
    --data-urlencode "FID_PERIOD_DIV_CODE=D" --data-urlencode "FID_ORG_ADJ_PRC=0" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); o=d.get("output2") or []; print("rt_cd",d.get("rt_cd"),"rows",len(o),"newest",o[0].get("stck_bsop_date") if o else None)'
  sleep 0.5
done
```

---

## 3. 주식현재가 시세 — `FHKST01010100`

`/uapi/domestic-stock/v1/quotations/inquire-price` — 현재가·시/고/저·누적거래량(당일 OHLCV 스냅샷). 장중 today 봉 비교용.

```bash
curl -s -G "$KIS_DOMAIN/uapi/domestic-stock/v1/quotations/inquire-price" \
  -H "authorization: Bearer $KIS_TOKEN" -H "appkey: $KIS_APPKEY" -H "appsecret: $KIS_APPSECRET" \
  -H "tr_id: FHKST01010100" -H "custtype: P" \
  --data-urlencode "FID_COND_MRKT_DIV_CODE=J" --data-urlencode "FID_INPUT_ISCD=036930" \
  | python3 -c 'import sys,json; o=json.load(sys.stdin).get("output",{}); print({k:o.get(k) for k in ("stck_prpr","stck_oprc","stck_hgpr","stck_lwpr","acml_vol")})'
```

---

## 4. 관심종목(멀티종목) 시세 — `FHKST11300006` ★ NXT 통합 검증용 (ADR 0118 R1 PENDING)

`/uapi/domestic-stock/v1/quotations/intstock-multprice` — 한 콜 최대 30종목, `inter2_oprc/hgpr/lwpr/prpr`·`acml_vol`·`acml_tr_pbmn`. 야간 일봉 snapshot이 **NXT 통합값을 주는지** 검증.

```bash
# J(KRX) vs NX(NXT) 로 같은 종목 시세 비교 → 종가/거래량이 다르면 통합 여부 판별
curl -s -G "$KIS_DOMAIN/uapi/domestic-stock/v1/quotations/intstock-multprice" \
  -H "authorization: Bearer $KIS_TOKEN" -H "appkey: $KIS_APPKEY" -H "appsecret: $KIS_APPSECRET" \
  -H "tr_id: FHKST11300006" -H "custtype: P" \
  --data-urlencode "FID_COND_MRKT_DIV_CODE_1=J"  --data-urlencode "FID_INPUT_ISCD_1=005930" \
  --data-urlencode "FID_COND_MRKT_DIV_CODE_2=NX" --data-urlencode "FID_INPUT_ISCD_2=005930" \
  | python3 -c 'import sys,json; o=json.load(sys.stdin).get("output") or []; [print(r.get("inter_shrn_iscd"),"clpr",r.get("inter2_prpr"),"vol",r.get("acml_vol"),"trpbmn",r.get("acml_tr_pbmn")) for r in o]'
```
> 장 마감 후(밤) 호출해야 그날 확정값. NXT 세션(~20:00) 이후 실행.

---

## 트러블슈팅

| 증상 | 원인/조치 |
|------|-----------|
| token len=0 | 키 오타 / 1분 1회 발급 제한 — 잠시 후 재시도. 기존 토큰 재사용 권장 |
| `rt_cd=1` `msg_cd=EGW00201` | 초당 호출 한도 초과 — `sleep` 늘리기 |
| `msg_cd=EGW00123` 등 토큰 만료 | 1번 재발급 |
| output2 빈 배열 | 해당 구간 데이터 없음(상장 이전·정지·KIS 미보유) |
| 한글 깨짐 | `| iconv -f utf-8` 또는 python3 파이프(위 예시) 사용 |

## 참고
- 전체 엔드포인트 카탈로그: [`kis-api-reference.md`](kis-api-reference.md)
- raw 함수 시그니처: open-trading-api `examples_user/domestic_stock/domestic_stock_functions.py`
