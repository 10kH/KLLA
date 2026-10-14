# 운영 · 지속성 노트

이 시스템은 맥미니 한 대에 의존한다. **git에 없는 것**과 **맥이 죽으면 사라지는 것**을 여기 정리한다.

---

## 1. 재부팅 후 자동 기동

다섯 개의 LaunchAgent가 있다. 설치는 각각 한 번만 하면 된다.

| 에이전트 | 역할 | 설치 | 재시작 |
|---|---|---|---|
| `com.woolab.lumella.luma-api` | 코치 엔진 (:8010) | `manage.sh luma-install` | KeepAlive |
| `com.woolab.lumella.luma-tunnel` | 공개 터널 + URL 재게시 | `manage.sh tunnel-install` | KeepAlive |
| `com.woolab.lumella.token-service` | 로컬 토큰 발급 (:8788) | `manage.sh install` | KeepAlive |
| `com.woolab.lumella.luma-backup` | 매일 04:30 백업 | `manage.sh backup-install` | 1회성 |
| `com.woolab.lumella.lan-address` | 5분마다 안경이 받는 주소 점검·재게시 | `manage.sh lan-install` | 5분 간격 |

상태: `manage.sh luma-status` / `tunnel-status` / `status` / `lan-status`

> ⚠️ **LaunchAgent는 사용자 로그인 후에 뜬다.** 이 맥은 자동 로그인이 꺼져 있는 것으로 보인다
> (`/etc/kcpassword` 부재). 즉 **재부팅하면 로그인 전까지 luma·터널이 전부 죽어 있다.**
> 밖에서 쓰다가 정전이라도 나면 복구가 안 된다.
>
> 선택지:
> - 자동 로그인 켜기 — 간단하지만 물리적 접근자가 곧 로그인 상태를 얻는다
> - LaunchDaemon(`/Library/LaunchDaemons`)으로 전환 — 로그인 없이 부팅 시 뜨지만 sudo 필요,
>   그리고 터널 스크립트가 쓰는 `vercel` CLI 인증이 사용자 계정에 묶여 있어 재설계가 필요하다
>
> 지금은 **로그인 필요** 상태로 두고 이 사실을 기록해둔다.

---

## 2. 백업 (git에 없고 재생성 불가한 데이터)

`ops/backup-luma.sh` — 매일 04:30, `~/Backups/luma/`에 14일치 보관.

무엇을 지키나:
- `data/luma.db` — 사용자 332명, 학습 세션 392건, 학습자 상태, 이미지 분석 기록
- `data/uploads`, `data/raw-media` — 업로드된 이미지 (DB의 `image_analyses`가 참조하므로 함께 없으면 복원해도 깨진다)

왜 `cp`가 아니라 `sqlite3 .backup`인가: 살아있는 DB를 그냥 복사하면 쓰기 도중 상태가 섞여
**복원할 때가 되어서야 깨진 걸 알게 된다.** 백업 직후 `PRAGMA integrity_check`로 검증하고,
실패하면 그 자리에서 에러를 낸다.

복원:
```bash
ops/launchd/manage.sh luma-uninstall
tar xzf ~/Backups/luma/luma-<stamp>.tar.gz -C <luma-api>/data/   # luma.db, uploads, raw-media
ops/launchd/manage.sh luma-install
```

검증 이력: 2026-07-29 스냅샷을 별도 경로에 풀어 `integrity=ok`, users/sessions/images가
원본과 일치(332/392/37)함을 확인했다.

### 서버 코드 반영 (KLLA-server main → 맥미니)

```bash
cd ~/workspace/KLLA-server && git pull --ff-only && git log --oneline -1
cd ~/workspace/KLLA && ops/launchd/manage.sh luma-install    # 서버가 몇 초(실측 6.5초) 꺼진다
```

`OK: luma-api healthy on :8010`이 나오면 끝. pull한 커밋이 `luma-api/pyproject.toml`이나 `uv.lock`을 바꿨으면
`luma-install` 전에 `cd ~/workspace/KLLA-server/luma-api && uv sync`.

### 데모 계정 학습자 상태 초기화 (2026-10-14, 한 번만)

안경이 쓰는 데모 계정(`learner@luma.app`)의 학습자 상태에 AI FESTA 부스에서 오간 말이 관심사·학습 초점으로
쌓여 있었다(관심사 "Bye-bye.", 초점 "그래서 이것도 앱이 두 가지 버전이 있거든요…"). 주제 없는 턴의 발화를 통째로
주제로 삼던 버그 때문이고, KLLA-server #22(`3b27de7`)에서 고쳤다. 이 값은 모든 개인화 프롬프트에 들어가서,
ETRI 답을 OpenAI로 바꾸는 품질 판정과 gpt_qa 답을 흐렸다. 관심사 점수는 줄어들지 않으므로 한 번 지운다.
지우면 다음 턴에 기본값(환경설정의 관심사)으로 다시 만들어진다. 학습 기록(대화·세션)은 건드리지 않는다.

**반드시 위 "서버 코드 반영"으로 #22(`3b27de7`) 이후가 떠 있는 상태에서** 한다. 옛 서버가 떠 있으면 바로 다시 쌓인다.

```bash
cd ~/workspace/KLLA && ops/launchd/manage.sh backup-now          # integrity=ok 확인
DB=~/workspace/KLLA-server/luma-api/data/luma.db
Q="FROM learner_states WHERE user_id=(SELECT id FROM users WHERE email='learner@luma.app')"
sqlite3 "$DB" "SELECT json_extract(current_focus,'$.topic') $Q;" # 지우기 전: 부스 문장이 보이면 맞다
sqlite3 -cmd ".timeout 5000" "$DB" "DELETE $Q; SELECT changes();" # 1이 나와야 한다
sqlite3 "$DB" "SELECT count(*) $Q;"                              # 0
```

되돌리기: 방금 만든 백업으로 `## 2`의 복원 절차.
(맥북에서 시험한 것: 같은 스키마의 빈 DB에 데모 계정과 부스 문장을 넣고 위 세 줄을 돌려 `1`, `0`; 그 뒤 서버 코드가
행을 기본값 `travel`·`daily life` / `continuation`으로 다시 만드는 것까지.)

---

## 3. 이 맥에만 있는 비밀·설정 (백업 대상)

전부 gitignore돼 있어 저장소에는 없다. 맥이 죽으면 **다시 만들어야** 한다.
폴더 이름은 2026-10-13 이후 기준(`~/workspace/KLLA`, `~/workspace/KLLA-server`; 맥미니 이전 절차는 [MACMINI-RENAME.md](MACMINI-RENAME.md)).

| 파일 | 담고 있는 것 | 잃으면 |
|---|---|---|
| `KLLA-server/luma-api/.env` | OpenAI 키, ETRI 설정, 모델 선택 등 37줄 | ETRI 설정 재수집 필요 |
| `KLLA/token-service/.env.local` | OpenAI 키, `LUMELLA_LOCAL_TOKEN` | 토큰 서비스 공유 시크릿 재발급 → Vercel 환경변수도 함께 교체 |
| `KLLA/local.properties` | 토큰 서비스 주소, 공유 시크릿, 데모 계정 | 앱 재빌드 설정 |
| `ELLA/local.properties` | Vercel 엔드포인트, `REALTIME_TOKEN_SECRET` | ELLA 3개 엔드포인트 인증 재설정 |

**API 키 자체는 제공자 대시보드에서 재발급 가능하지만, 우리가 생성한 공유 시크릿은 아니다.**
Vercel 환경변수(`LUMELLA_LOCAL_TOKEN`, `REALTIME_TOKEN_SECRET`, `LUMA_BASE_URL`)와 짝이 맞아야
하므로, 한쪽만 잃어도 양쪽을 새로 맞춰야 한다.

→ 이 네 파일은 암호 관리자나 개인 백업에 따로 보관할 것. (저장소에 넣지 말 것)

---

## 4. 알려진 취약점

| 항목 | 현황 | 영향 |
|---|---|---|
| 데모 계정 `luma1234` | 터널로 공개망 노출, **로그인 시도 제한 없음** | 대입 공격 시 크레딧 소모 API 탈취 |
| `/docs`, `/openapi.json` | 공개 | 42개 경로 전부 노출(공격 지도) |
| quick tunnel URL | 재시작마다 변경 | 자동 재게시로 흡수되나, 앱 실행 중 변경 시 다음 실행까지 구 URL 사용 |
| 집 인터넷 | 단일 경로 | 끊기면 코치 불가(음성은 Vercel이라 유지) |

가장 시급한 것은 **데모 계정 비밀번호**다. luma 코드를 건드리지 않고 DB와 앱 설정만 바꾸면 된다.
