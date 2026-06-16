# magicJar 초기 설치 가이드

> 신규 환경(개인 PC·서버)에서 magicJar를 처음 띄우는 절차. 운영자(설치자) 기준 1회 작업.

## 1. 사전 준비물

| 항목 | 발급 경로 | 비고 |
|---|---|---|
| **KIS OpenAPI 실전 키** (필수) | https://apiportal.koreainvestment.com | 모든 데이터 조회(시세·캔들·종목 메타) source. 발급 시 IP 등록은 보통 불요. |
| **KIS OpenAPI 모의투자 키** (선택) | 동일 | 모의 매수/매도 주문 시에만 사용. 데이터 조회는 절대 안 봄. |
| **OPENDART API 키** (선택) | https://opendart.fss.or.kr/uss/umt/EgovMberInsertView.do | 회원가입 후 무료 발급. 일 20,000건 한도. 종목 상세의 매출액·영업이익·EPS 등 재무 표시용. |
| **Anthropic API 키** (선택) | https://console.anthropic.com | AI 분석 보조 활성 시. 미설정 시 mock fallback. |
| Docker Desktop | https://www.docker.com/products/docker-desktop | 4.x 이상 권장. |
| Git | 표준 설치 | |

## 2. 설치 단계

### 2.1 저장소 clone + 환경 설정

```bash
git clone https://github.com/sp-daewoon/magicJar.git
cd magicJar
cp .env.example .env
```

`.env` 편집 — 사용자 키 입력:

```bash
# 한국투자증권 OpenAPI (모의투자) — 모의 매매 시 사용
KIS_MOCK_APP_KEY=발급받은_모의_App_Key
KIS_MOCK_APP_SECRET=발급받은_모의_App_Secret

# 한국투자증권 OpenAPI (실전) — 모든 데이터 조회 source
KIS_APP_KEY=발급받은_실전_App_Key
KIS_APP_SECRET=발급받은_실전_App_Secret

# 계좌
KIS_ACCOUNT_CANO=8자리_계좌번호
KIS_ACCOUNT_PRDT=01

# DART (선택)
OPENDART_API_KEY=발급받은_DART_키

# 마스터 암호화 키 (DB 영속 키 AES-256-GCM 복호화 전용)
# 32바이트 base64 — 운영자 발급. 예시:
MAGICJAR_SECRET_MASTER_KEY=05UpBZJr7yfmQ4TeHWiG5OngESQVgi3t+Qxrk/SoMt4=

# 기본 운영 (필요 시 변경)
SPRING_PROFILES_ACTIVE=mock
API_HOST_PORT=18080
```

### 2.2 마스터 키 발급 (운영자 1회)

```bash
# Linux/Mac에서 32바이트 base64 키 생성
openssl rand -base64 32
```

생성된 결과를 `.env`의 `MAGICJAR_SECRET_MASTER_KEY`에 입력. **변경 시 DB의 모든 암호화 key를 옛 키로 복호화 후 새 키로 재암호화 필요** — 운영 중 키 회전은 admin endpoint(향후 추가) 사용.

### 2.3 컨테이너 부팅

```bash
# 모의 모드 (개발·학습)
docker compose -f docker/docker-compose.yml \
               -f docker/docker-compose.dev.yml \
               --env-file .env up -d
```

9개 컨테이너 모두 healthy 도달 확인 (약 30초~2분):
- postgres·kafka·redis
- api-app·batch-app·consumer-app·frontend

```bash
docker compose ps
```

모든 컨테이너 `Status: healthy` 확인.

### 2.4 첫 진입 + 설정

브라우저에서 `http://localhost:3000` 접속.

**현 단계**: 헤더 우측 ⚙️ Settings 버튼 클릭 → 카드별 키 등록:
- **모의투자 KIS 카드** (선택) — 모의 매매 시
- **실전 KIS 카드** (필수) — 모든 데이터 조회 source
- **DART 카드** (선택) — 재무 표시

**향후 setup wizard**: 첫 진입 시 자동 wizard 노출 예정 (다음 사이클 기능).

### 2.5 종목 마스터파일 수집 (선택)

설정 화면 또는 admin에서 종목 마스터파일 1회 수집:
- 헤더 검색바 동작 위해 `instrument` 테이블 채우기
- 약 1분 소요 (KOSPI·KOSDAQ 4,300+ 종목)

## 3. 일상 운영

### 3.1 모드 전환 — 모의/실전

상단 모드 인디케이터(노랑·빨강 배지)로 현재 모드 확인:
- **노랑 (MOCK)** — 주문은 가짜 거래
- **빨강 (REAL)** — 실제 돈 거래

⚙️ Settings에서 `trading_mode` 변경 → DB 영속 → 즉시 반영.

### 3.2 데이터 정책

| 데이터 종류 | source | 키 |
|---|---|---|
| 일봉 | KIS REST `FHKST03010100` | **실전 키** (모의 미지원 endpoint) |
| 분봉 (M1) | Yahoo Finance | 키 불요 (공개 API) |
| 분봉 (M5/M15/M30/H1) | M1 → 백엔드 합성 | 별도 호출 X |
| 종목 메타·재무 | KIS CTPF1002R + DART fnlttSinglAcntAll | 실전 KIS + DART |
| 시세 실시간 | KIS WebSocket H0STCNT0 | 실전 키 |
| 모의/실전 주문 | KIS REST `TTTC0011U` 등 | trading_mode 따라 |

### 3.3 키 회전

**KIS·DART 키 갱신** — UI ⚙️ Settings에서 즉시 갱신 (재배포 불요).

**마스터 암호화 키 회전** — DB 모든 암호화 row 마이그레이션 필요. 별도 admin 절차 (향후).

## 4. 트러블슈팅

### "차트 데이터 없음"
- 신규 종목 첫 진입 시 30~60초 cold-start backfill 진행 중. 기다린 후 새로고침.
- 또는 Yahoo Finance가 KOSDAQ 일부 종목 미지원 가능 — 직접 호출로 검증.

### "종목 메타 빈 panel"
- KIS 실전 키 미등록 → ⚙️에서 등록
- DART 키 미등록 → 재무 항목만 빈
- 키 등록했는데도 빈 → API 활성화 안 됐을 수 있음 (`https://apiportal.koreainvestment.com` 확인)

### "지수 카드 prevClose 0"
- KIS 실전 키 미등록 또는 모의 키 사용 중. 실전 키 확인.

### "모드 토글 안 됨"
- master key 미설정 또는 잘못 — `MAGICJAR_SECRET_MASTER_KEY` 32바이트 base64 확인

### 컨테이너 healthy 안 됨
- `docker logs magicjar-{api,batch,consumer}-app` 첫 100줄 확인
- master key 부재로 인한 `IllegalStateException` 빈번

## 5. 다음 단계

- [매매 정책 가이드](order-policy.md) — KillSwitch·DailyOrderLimit·trading_mode 운영
- [캔들 캐시 가이드](candle-cache-guide.md) — LRU eviction·Yahoo Finance backfill
- [MCP 서버 가이드](mcp-server-guide.md) — 외부 AI 클라이언트 연동
