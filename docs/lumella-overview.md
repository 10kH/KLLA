# lumella — 글래스 한국어 튜터 (luma 엔진 + ELLA 외피)

구성일: 2026-07-21. 승인 근거: ralplan 합의 계획(세션 019f81e8) + ultragoal 실행 런.

> **경고: 이 디렉토리(`TUTOR/lumella/`) 위에서 `git init` / 재초기화 금지.**
> 하위에 독립 git repo 2개가 중첩돼 있다. 상위는 의도적으로 비버저닝이다.

## 구조 (E-C: 중첩 독립 repo 2개)

| 경로 | 정체 | repo |
|---|---|---|
| `luma/` | 튜터링 엔진 (FastAPI `luma-api` + Next.js `luma-web`). **독립 업데이트 케이던스.** | 독립 git (HEAD 핀은 `glasses/contract-tests/COMPAT.md`) |
| `glasses/` | RayNeo X3 Pro 글래스 앱 (ELLA-MA fast/slow 외피). 원격(승인 후): `10kH/lumella` | 독립 git (신규) |

결합은 **HTTP `/v1` 계약뿐**이다. glasses는 `luma-adapter` 모듈만 luma를 알고,
`tutor-contract`의 `TutorBrain` 포트(5-op + connect) 뒤에서 소비한다.
호환성 방어: contract fixtures(steering-nonempty 의무) + `/v1/capabilities` 협상 + StalenessGuard + COMPAT.md SHA 핀.

## 아키텍처 요지

- **fast path**: OpenAI realtime 음성 세션이 대화를 단독 소유(<500ms 목표). 토큰은 luma 무관 `glasses/token-service`(:8788)가 발급.
- **slow path**: luma가 W-1 `response_mode=coach`로 교정/스티어링 증거만 공급. luma 다운/스큐/미지원 시 voice-only degrade — 튜터는 침묵하지 않는다.
- v1 제품 언어: **한국어 튜터링** (ETRI 경로 직결).

## 코치 라우팅 힌트 (2026-10-17)

주제 대화에서 서버는 매 턴 gpt-5.4로 경로를 골랐다(약 1.5초, 발화를 OpenAI에 한 번 더 보냄).
앱이 질문이 아닌 게 분명한 문장이면 `routeHint: "topic_chat"`을 보낸다
(`app/.../voice/TopicRouteHint.kt`, `TurnEvidence.routeHint`). 서버에 이미 있는 API다:
힌트가 있으면 서버는 경로 선택 모델을 부르지 않는다. 질문(`?`, 뭐/왜/어떻게…, -나요/-까요…),
부탁(알려 주세요, 다시…), 주제 고르기, 인사·감사, 사진 턴, 주제 없는 대화는 힌트 없이 보내
서버가 지금처럼 고른다.

- 바뀌는 것은 코치(slow path)의 경로 선택뿐이다. **음성은 그대로 OpenAI realtime**이 만든다.
- 글래스 측정(script v6, 30턴씩, 주입 발화, aaai27 `artifacts/demo-scenario/hint-*-app.txt`):
  힌트 턴 코치 중앙값 1.39초(split)·1.28초(lagged)·1.30초(최종 빌드 split), 힌트 없는 턴
  2.65·2.91·3.03초. 힌트 전 같은 대본 코치 중앙값은 3.3~3.5초였다. 질문 3줄은 모두 예전처럼
  openai/gpt_qa(질문에는 힌트를 붙이지 않으니 서버 요청이 예전과 같다).
- 알려진 빈칸: 물음표·의문사 없는 질문("더 좋은 말 있어요")은 힌트가 붙는다.

## 대화 단계 제어 (2026-10-17 현재)

발표 자료의 단계: 인사 → 질문 → 대답 → 대응 → 추가 질문. 코드에서 실제로 하는 것:

| 단계 | 어디서 | 주제 대화(글래스 기본)에서 |
|---|---|---|
| 인사·첫 질문 | KLLA `TopicGuidance.Opening` (CHOOSE / ON_TOPIC / WELCOME_BACK) | 있음 |
| 대응(고쳐 말해 주기·늘려 말하기) | KLLA 페르소나 + `TopicGuidance.SPLIT_FIRST` | 있음 |
| 추가 질문 | ETRI Tango가 턴마다 다음 질문을 만듦 → KLLA `guideInstruction` / `SplitReply` | 있음 (단계 상태 없이 턴 단위) |
| 마무리 | KLLA `TopicGuidance.learnerIsClosing` → 질문 없이 인사, split은 둘째 부분 없음 | 10/17 추가 |
| 단계 상태 추적 | 서버 ETRI `dialogueStates`(qaIdx/nextQaIdx) | **없음** — 상황극·읽기 경로에만 |

- 서버: `luma-api/src/luma_api/services/orchestrator/tools/scenario.py`, `reading.py`, `repair.py`
  (`_scenario_next_move`), `coach.py`(`_latest_dialogue_state`)만 단계를 안다. `policy_state.py`는
  세션 목표·학습 기억·확인 상태를 두고 단계는 두지 않는다. 주제 대화(`topic_chat`)는 단계 상태가 없다.
- 마무리 빈칸(10/17 글래스 기록, script v6 마지막 줄 "오늘 이야기 재미있었어요. 고마워요!"):
  고치기 전 lagged는 "…또 나누자, 어때요?"로 다시 물었고, split은 인사 뒤에 Tango 줄로 둘째 부분을
  또 말했다. 고친 뒤 split은 "둘째 부분 없음 (학습자가 대화를 마무리)", lagged는 질문 없이 인사했다(각 1회).
- 단계 상태를 주제 대화에도 두는 일은 서버(Tango) 쪽 일이라 KLLA에서는 하지 않았다.

## 서버 쪽 남은 일 (KLLA-server, 우윤규 씨 소관 — 메모만)

- 웹 대화의 품질 검사 기준(글래스 코치 턴은 #28로 꺼짐).
- LLM 답을 SLM 학습 쌍으로 내보내기(`rejectedCandidates`는 #27로 남기 시작).
- `selection_failed_stay`.
- 주제 대화의 단계 상태(위 표).

## 주의

- `luma/ELLA-main/`은 **reference-frozen** — 구 글래스 클라이언트 참고용, 빌드 비대상. 신규 개발은 전부 `glasses/`. (repo 내 README 마킹은 첫 승인 luma 커밋에 포함 예정.)
- `luma/` HEAD 이동 검증 기록: 이동 전=후 `da111e5`, clean (2026-07-21).
- 레거시 계보: `TUTOR/LEGACY/` (ELLA=ELLA-MA, ELLAs, ELLAL, luma-codex).
