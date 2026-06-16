# 새 PC 마이그레이션 런북 (magicJar 완전 복원)

> **목적**: PC 포맷·교체 시 magicJar를 새 PC에서 완전히 되살리기 위해 "무엇을 챙기고, 무엇은 자동 복원되며, 어떤 순서로 복구하는가"를 단계별로 LOCK.
>
> **권위**: ADR 0096(P0-OPS-4 DR/backup) + ADR 0105(장기 1m 보관/복구) + R-4 runbook(`docs/operations/runbook/R4-db-recovery.md`). 본 런북은 정책 변경이 아니라 그 **집행·집계 가이드**.
>
> **검증 시점**: 2026-06-06. dump 56MB(custom format -Fc, TABLE DATA 56건, flyway V70 포함) + redis snapshot 71K 실측. 임시 DB `magicjar_restore_test` 복원 검증 — 테이블 56건·flyway V70·candle 254만 row·user_kis_credential 2건 운영 DB와 row-level 일치(`scripts/backup/pg-restore.sh` evidence).

---

## ★ 한 장 요약 — 새 PC에 반드시 들고 가야 할 것

> **`.env` + 최신 pg dump 두 개는 "한 세트"다. 둘 중 하나라도 없으면 사용자 데이터(특히 KIS 키)는 영구 복원 불가.**

| 챙길 것 | 이유 | 없으면 |
|---------|------|--------|
| **`.env`** (시크릿 + **AES 마스터 키** `MAGICJAR_SECRET_MASTER_KEY`) | git 제외 + 유일본 | KIS 키·AI 키 영구 소실 + pg dump가 있어도 user_kis_credential 복호화 불가 |
| **최신 `_workspace/backups/postgres/daily/*.dump`** | 사용자 데이터 전체 + 1년 초과 1m 캔들 유일 복원원 | 전략·매매일지·1년 초과 캔들 영구 소실 |
| (보조) 최신 `_workspace/backups/redis/snapshots/*.rdb` | cache 빠른 복구 | 없어도 자연 재구성 (필수 아님) |

코드·문서·DB 스키마·일반 캔들은 자동 복원되므로 **백업 불필요**(§A). 위 표의 B계층(§B)만 외부 매체로 챙기면 된다.

---

## A계층: 자동 복원 (백업 불필요)

새 PC에서 git clone + 부팅만 하면 자동으로 회복되는 것. **백업 대상 아님.**

### A-1. 코드·문서·스크립트 — git clone

- 출처: `git clone https://github.com/sp-daewoon/magicJar.git`
- ADR·CHANGELOG·운영 문서·`scripts/`·Gradle 멀티모듈·frontend 전부 git tracked → clone 1회로 복원.

### A-2. DB 스키마 + seed 데이터 — Flyway 자동

새 PC의 빈 PostgreSQL에 앱이 부팅하면 Flyway가 `api/src/main/resources/db/migration/V1~V70` 을 자동 적용해 스키마 + seed를 재생성한다. **빈 DB로 새로 시작하는 경우(=사용자 데이터를 버리고 깨끗이 재시작)에만 해당**. 사용자 데이터를 보존하려면 §C-4 pg_restore 경로를 쓴다(이 경우 dump 안 flyway_schema_history가 V70까지 이력째 복원).

전수 grep으로 확인한 **seed(INSERT) 포함 마이그레이션**:

| 마이그레이션 | seed 대상 | 내용 |
|-------------|----------|------|
| `V1__baseline.sql` | `system_config` | 단일 row(id) 기본 설정 |
| `V6__market_calendar_and_reject_audit.sql` | `market_calendar` (STATIC_SEED) | 정적 휴장 시드(source='STATIC_SEED') — 이후 KIS_CHK_HOLIDAY로 보강 |
| `V16__risk_config.sql` | `risk_config` DEFAULT | H-1 default seed(max_concurrent_positions=5 등 컬럼 DEFAULT, 부재 시 기본 적용) |
| `V50__prompt_template.sql` | `prompt_template` **5종** | AI 프롬프트 템플릿 5건(type/version/template_text/is_active) |
| `V51__user_preference.sql` | `user_preference` | trend MA·RS 기간 기본 선호 |
| `V62__kill_switch_state_seed.sql` | `kill_switch_state` | KillSwitch 초기 OFF state seed |
| `V70__strategy_research_ai_discovery_preset.sql` | `strategy_research_ai_discovery_preset` **3종** | AI 종목발굴 intent preset 3건 (is_builtin) |

> ⚠️ **V70 주의**: V70은 현재 **로컬 main에만 존재**하며 origin 흡수 PR 진행 중이다. 본 런북은 V70 포함을 기준으로 작성했다. 새 PC clone 시 origin에 V70이 머지되어 있어야 AI 발굴 preset 3종이 Flyway로 자동 seed된다. 머지 전 시점이면 §C-4 pg_restore 경로(dump에 V70 적용 이력 + preset row 포함)로 복원한다.

### A-3. 캔들·뉴스·공시·지수 — KIS/DART/네이버 재수집

| 데이터 | 재수집 경로 |
|--------|------------|
| 캔들(일/주/월/년 + 분봉) | 앱 부팅 시 cold-start + boot/scheduled reconciliation(ADR 0105). `.env`의 `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT`·`MAGICJAR_MARKET_CALENDAR_SYNC_AT_BOOT` true면 자동. 종목 상세 진입 시 gap fill(`FHKST03010230`) |
| 뉴스 | 네이버 RSS/Open API 재크롤(`NAVER_NEWS_RSS_URL` 등) |
| 공시 | DART 재수집(`OPENDART_API_KEY`) |
| 지수 snapshot | KIS 재수집 |

> ⚠️ **1m 캔들 1년 한도 경고 (ADR 0105 §라인 63·145·225 정합)**:
> KIS `FHKST03010230`은 **서버 보관분 최대 1년**까지만 과거 1m을 돌려준다. **1년을 초과한 1m 캔들은 KIS 표준 API로 복구 불가** — 이 구간은 **pg dump가 유일한 복원 수단**이다. 따라서 장기 백테스트·전략 window가 1년 초과 1m에 의존한다면, 그 데이터는 A계층(자동 재수집)이 아니라 **B계층(반드시 pg dump 백업)** 으로 다뤄야 한다. dump 없이 PC를 포맷하면 1년 초과 1m은 영구 구멍(`UNRECOVERABLE`)이 되고 해당 window의 자동매매는 fail-closed로 차단된다.

### A-4. instrument 마스터 — KIS sync

- KIS KOSPI/KOSDAQ `.mst` 파일 동기화(`MAGICJAR_KIS_KOSPI_MST_URL`·`MAGICJAR_KIS_KOSDAQ_MST_URL`) → 부팅 시 자동(`MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true`).

### A-5. Redis — 휘발성, 자동 재구성

- KIS OAuth 토큰 캐시·Bucket4j rate limit·WS fanout·Screener cache 등은 전부 cache/임시 state(ADR 0096 §3-3). 손실돼도 자연 회복.
- snapshot(`*.rdb`)은 **보조** — 빠른 복구용일 뿐 필수 아님.

---

## B계층: 반드시 수동 백업 (유일본 — 잃으면 영구 소실)

> git에도 KIS에도 없는 데이터. **외부 매체(클라우드/USB)로 직접 챙겨야 한다.**

### B-1. `.env` — 시크릿 + AES 마스터 키 (백업 1순위)

`.env`는 `.gitignore` 41행으로 git 제외 → **저장소에 절대 없다.** 포함 시크릿:

| 항목 | 키 | 비고 |
|------|----|----|
| KIS app key/secret (조회용 prod) | `MAGICJAR_KIS_APP_KEY` · `MAGICJAR_KIS_APP_SECRET` | |
| KIS app key/secret (주문용 vps) | `MAGICJAR_KIS_VPS_APP_KEY` · `MAGICJAR_KIS_VPS_APP_SECRET` | |
| **AES 마스터 키** | `MAGICJAR_SECRET_MASTER_KEY` | **AES-256-GCM 32바이트 base64** |
| AI 키 | `ANTHROPIC_API_KEY` · `MAGICJAR_AI_OPENAI_API_KEY` · `MAGICJAR_AI_GEMINI_API_KEY` | |
| DART 키 | `OPENDART_API_KEY` | |
| 네이버 Open API | `NAVER_OPEN_API_CLIENT_ID` · `NAVER_OPEN_API_CLIENT_SECRET` | |
| DB/Redis 좌표·비번 | `SPRING_DATASOURCE_*` · `SPRING_REDIS_*` · `KAFKA_BOOTSTRAP` | |
| 계좌 식별자 | `MAGICJAR_KIS_ACCOUNT_CANO` · `MAGICJAR_KIS_ACCOUNT_PRDT` · `MAGICJAR_KIS_HTS_ID` | |

> ┌─────────────────────────────────────────────────────────────────────┐
> │ ⚠️ **".env + pg dump는 한 세트" — AES 키 없으면 dump가 있어도 복호화 불가** │
> ├─────────────────────────────────────────────────────────────────────┤
> │ DB의 `user_kis_credential` 테이블은 KIS 키를 **평문이 아니라 AES-256-GCM    │
> │ 암호문(`app_key_enc`·`secret_key_enc` BYTEA)** 으로 저장한다              │
> │ (`api/.../secret/AesGcmCipher.kt`, 키 = `MAGICJAR_SECRET_MASTER_KEY`).  │
> │ 따라서 **pg dump를 아무리 잘 복원해도, `.env`의 AES 마스터 키가 다르면      │
> │ KIS 키 복호화에 실패**해 매매·시세 연결이 안 된다.                          │
> │ → `.env`(특히 `MAGICJAR_SECRET_MASTER_KEY`)와 pg dump를 **반드시 함께**     │
> │   같은 시점·같은 매체로 백업·복원한다.                                     │
> └─────────────────────────────────────────────────────────────────────┘

### B-2. pg dump — 사용자 데이터 전체

- 위치: `_workspace/backups/postgres/daily/YYYY-MM-DD.dump` (custom format `-Fc`)
- 포함(2026-06-06 실측 56 테이블 — 핵심):
  - `user_kis_credential`(AES 암호문) · `user_account` · `user_preference`
  - `strategy` · `watchlist` · `risk_config` · `kill_switch_state`
  - `trading_journal`/`journal_note` · `pnl_daily` · `performance_summary`
  - `backtest_run`/`backtest_trade`/`backtest_position` · `strategy_position`
  - `strategy_research_ai_discovery_state`/`_preset`(사용자 생성 프리셋 포함)
  - `order_*`/`audit_trail`/`order_reject_audit`/`mcp_audit`(주문·감사 이력)
  - `notification_outbox`(Outbox 영속 권위 — ADR 0088)
  - `system_config` · `market_calendar`/`market_calendar_event`
  - **`candle`(장기 보존 — 1년 초과 1m의 유일 복원원, §A-3 경고 정합)**

### B-3. `_workspace/backups/`는 git 제외 → 외부 매체 복사 의무

- `.gitignore` **59행** `_workspace/backups/` → repo push로 **절대 안 올라간다.**
- 따라서 dump·snapshot은 **외부 매체(클라우드 폴더·USB·외장 SSD)로 직접 복사**해야 한다. backup-setup.md §외부 매체 동기화 참조(rsync 라인).

### B-4. 기타 시크릿 파일 (.gitignore 전수 확인 — 존재 시만)

`.gitignore` 40~48행 기준 git 제외 시크릿 패턴 — 새 PC에 옮겨야 하는 것(존재할 때만):

- `.env` (B-1)
- `application-local.yml` / `application-local.yaml`
- `application-secret.*`
- `src/main/resources/application-local.*` / `application-secret.*`
- `*.p12` · `*.pem` (인증서 — pre-commit 차단 패턴, 존재 시)
- `docker/docker-compose.prd.yml` (.gitignore 86행 — prd 운영 시만)

### B-5. (선택) Claude 메모리 — 운영 비필수, 권장

- 위치: `~/.claude/projects/-Users-ak-song-magic-ch1/memory/`
- magicJar 운영 자체엔 불필요하나, 오케스트라/에이전트 컨텍스트 연속성을 위해 권장.

---

## C계층: 새 PC 복원 절차 (단계별 · 복붙 가능)

### C-1. 사전 설치

```bash
# macOS 기준
# 1) Docker Desktop — https://www.docker.com/products/docker-desktop (설치 후 실행)
# 2) git, JDK 21, node + pnpm
brew install git openjdk@21 node
npm install -g pnpm
# 3) Claude CLI — AI 종목발굴 bridge(claude -p / scripts/claude-discovery-bridge.mjs)용
#    https://docs.claude.com/claude-code 참조
```

확인:

```bash
docker --version && git --version && java -version && node -v && pnpm -v
```

### C-2. git clone + .env 복원

```bash
git clone https://github.com/sp-daewoon/magicJar.git /Users/ak-song/magic_ch1
cd /Users/ak-song/magic_ch1

# 외부 매체에 백업해 둔 .env 를 repo 루트로 복원 (B-1)
cp /Volumes/<백업매체>/magicjar/.env .env
# ★ .env 안 MAGICJAR_SECRET_MASTER_KEY 가 dump 시점과 동일한지 반드시 확인 (B-1 경고)

# 기타 시크릿 파일도 존재했다면 복원 (B-4)
# cp /Volumes/<백업매체>/magicjar/application-local.yml ...
```

### C-3. 인프라 컨테이너 기동

```bash
cd /Users/ak-song/magic_ch1
docker compose -f docker/docker-compose.infra.yml up -d   # postgres · kafka · redis
# postgres ready 대기
until docker exec magicjar-postgres pg_isready -U magicjar -d postgres; do sleep 2; done
```

### C-4. pg_restore — 사용자 데이터 복원

> dump 안에 `flyway_schema_history`가 포함되므로, **restore가 마이그레이션 이력(V70까지)도 함께 복원**한다. 따라서 복원 후 앱이 부팅해도 Flyway가 "이미 V70까지 적용됨"으로 인식해 중복 적용하지 않는다.
>
> dump 포맷은 custom format(`-Fc`, PGDMP magic). 본 런북은 `scripts/backup/pg-restore.sh`(pg-dump-daily.sh 짝) 사용을 권장한다 — dry-run·안전 백업·복원 후 검증을 내장.

**권장: 스크립트 사용**

```bash
cd /Users/ak-song/magic_ch1
# 외부 매체의 dump 를 daily 폴더로 복원
mkdir -p _workspace/backups/postgres/daily
cp /Volumes/<백업매체>/magicjar/<날짜>.dump _workspace/backups/postgres/daily/

# 1) (권장) dry-run 으로 내용 미리보기
bash scripts/backup/pg-restore.sh --dry-run

# 2) 운영 DB(magicjar)에 실제 복원 — 이중 게이트 --confirm-prod 필수
bash scripts/backup/pg-restore.sh --target-db magicjar --confirm-prod
# 종료 로그: "검증 OK — 테이블 N건 복원 + flyway VNN 회복"
```

**수동: 직접 pg_restore (R-4 runbook §3-2 정합)**

```bash
LATEST=$(ls -t _workspace/backups/postgres/daily/*.dump | head -1)
docker cp "$LATEST" magicjar-postgres:/tmp/restore.dump
# --clean --create 로 DB 삭제 후 재생성 (postgres DB에 접속)
docker exec magicjar-postgres pg_restore --clean --create --if-exists \
  -U magicjar -d postgres /tmp/restore.dump
docker exec magicjar-postgres rm /tmp/restore.dump
# 검증
docker exec magicjar-postgres psql -U magicjar -d magicjar -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
docker exec magicjar-postgres psql -U magicjar -d magicjar -tAc \
  "SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1;"
```

> **빈 DB로 새로 시작(사용자 데이터 버림)** 하려면 C-4를 건너뛰고 C-5로 진행 — 앱 부팅 시 Flyway가 V1~V70 자동 적용 + seed(§A-2)만 생성된다.

### C-5. 앱 전체 기동

```bash
cd /Users/ak-song/magic_ch1
scripts/build-and-deploy.sh   # gradle bootJar + docker compose build/up (7 컨테이너)
```

### C-6. 복원 검증 체크리스트

```bash
# 1) 통합 스모크 3 시나리오
scripts/smoke.sh

# 2) 4 모듈 docker 로그 에러 0 확인
for s in magicjar-api magicjar-batch magicjar-consumer magicjar-frontend; do
  echo "=== $s ==="; docker logs --tail 30 "$s" 2>&1 | grep -iE 'error|exception' | head;
done
```

화면 검증(브라우저):

- [ ] 로그인 / Dashboard 진입
- [ ] Watchlist 표시 (복원된 사용자 watchlist)
- [ ] **KIS 키 동작 — 시세 수신** (`.env` AES 키 ↔ dump `user_kis_credential` 복호화 성공의 결정적 증거. 실패 시 B-1 경고 박스 점검)
- [ ] 캔들 차트 표시
- [ ] 전략·매매일지·백테스트 이력 표시

### C-7. 백업 cron 재등록 (필수 — 새 PC에서 백업 0 상태이므로)

새 PC는 cron이 비어 있다. `docs/operations/backup-setup.md` §2 절차로 재등록:

```bash
crontab -e   # 아래 2 라인 추가
```

```cron
0  3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/pg-dump-daily.sh   >> _workspace/backups/postgres/cron.log 2>&1
10 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1
```

등록 확인: `crontab -l | grep magicjar` — 자세한 절차·진단·macOS Full Disk Access 권한은 `docs/operations/backup-setup.md`.

---

## 관련 문서

| 문서 | 위치 |
|------|------|
| backup cron 셋업 | `docs/operations/backup-setup.md` |
| restore 스크립트 | `scripts/backup/pg-restore.sh` (dump 짝: `pg-dump-daily.sh`) |
| R-4 DB 복구 runbook | `docs/operations/runbook/R4-db-recovery.md` |
| ADR 0096 (DR/backup 권위) | `_workspace/arch/adr/0096-dr-backup-procedure.md` |
| ADR 0105 (장기 1m 보관/복구) | `_workspace/arch/adr/0105-candle-reconciliation-and-long-horizon-1m-retention.md` |
| 초기 셋업 | `docs/operations/initial-setup.md` |

---

## 변경 이력

| 날짜 | 변경 | 작성 |
|------|------|------|
| 2026-06-06 | 신규 발행 — 새 PC 마이그레이션 3계층 인벤토리(A 자동/B 수동백업/C 복원절차) + ".env+pg dump 한 세트" AES 경고 + 1년 초과 1m pg dump 유일 복원 경고(ADR 0105) + pg-restore.sh 검증 evidence 정합. 정책 변경 0(ADR 0096·0105 집행). | backend-core |
