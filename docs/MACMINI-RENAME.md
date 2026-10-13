# 맥미니에서 할 일 — 폴더 이름 바꾸기 (lumella → KLLA, luma → KLLA-server)

2026-10-13. GitHub 저장소와 맥북 폴더는 이미 바꿨다. 이 문서는 **맥미니에서 손으로** 할 일이다.

> **완료 — 2026-10-13 19:01~19:03 (서비스 정지 약 2분).** 맥미니의 실제 위치는 `~/workspace/lumella`, `~/workspace/luma`였고
> 지금은 `~/workspace/KLLA`(dd54e55), `~/workspace/KLLA-server`(f0ccd7f). 백업 두 번(19:01 옮기기 전, 19:03 옮긴 뒤) 모두
> integrity=ok. 안경이 쓰는 길(Vercel → 새 터널 → 서버)에서 `"coach": true`, 맥북에서 코치 턴 2개 실제 응답(ETRI free_chat).
> 문서와 달랐던 점 셋 — 아래 본문은 고쳐 두었다:
> 1. LaunchAgent가 다섯 개였다. `com.woolab.lumella.lan-address`는 손으로 만든 것이라 manage.sh 밖에 있었고 옛 경로를 가리켰다
>    (그날 손으로 고침). 이제 템플릿과 `manage.sh lan-install`이 있다.
> 2. `luma-install`이 서버가 뜨기 전에 확인해 "not healthy"라고 했다(몇 초 뒤 정상). 이제 30초까지 기다린다.
> 3. `uv sync --reinstall`이 손으로 깔아 둔 패키지를 지웠다(luma-evolution, gepa, pytest, ruff 등). openai도 3.6.0 → lock의
>    2.53.0. 서버는 luma_evolution을 import하지 않아 문제없다. luma-evolution은 자기 폴더의 pyproject로 따로 돌린다.
>    pytest·ruff가 필요하면 `cd ~/workspace/KLLA-server/luma-api && uv sync --extra dev`.
>
> **남은 한 번 (맥미니):** 손으로 고친 lan-address plist를 템플릿 것으로 바꾼다.
> `cd ~/workspace/KLLA && git pull --ff-only && ops/launchd/manage.sh lan-install && ops/launchd/manage.sh lan-status`
> (`last exit code = 0`, 로그 끝에 `nothing to do` 또는 `published and verified`가 나오면 끝.)

- 걸리는 시간: 15분 정도. 그중 **5분쯤 KLLA 서버·터널·토큰 서비스가 멈춘다.** 그동안 안경으로 대화하면 안 된다.
- **모든 명령을 터미널 창 하나에서 순서대로** 친다. 1단계에서 만든 변수(`OLD_GLASSES`, `OLD_SERVER`)를 끝까지 쓴다.
- 중간에 "멈추세요"라고 적힌 결과가 나오면 거기서 멈추고 결과를 붙여서 알려 주면 된다.

바꾸는 것: 맥미니의 두 폴더 이름, 그 폴더를 가리키는 git 주소와 LaunchAgent 4개, 서버 가상환경.
바꾸지 않는 것: 저장소 안의 `luma-api`·`luma-web` 폴더, 앱 ID `com.woolab.lumella`, LaunchAgent 이름(`com.woolab.lumella.*`),
`~/.config/lumella/`, `~/Backups/luma/`, Vercel 쪽. 전부 학습자에게 안 보이는 이름이라 그대로 둔다
([BRAND.md](https://github.com/10kH/KLLA-server/blob/main/docs/BRAND.md) "저장소 안에 남은 옛 이름").

---

## 1. 지금 폴더가 어디 있는지 확인

맥미니의 폴더 위치는 문서마다 다르게 적혀 있다(`~/workspace/lumella`, `~/workspace/TUTOR/lumella/glasses`).
**실제로 돌고 있는 LaunchAgent가 가리키는 곳**이 정답이다.

```bash
for f in ~/Library/LaunchAgents/com.woolab.lumella.*.plist; do
  echo "== $(basename "$f")"
  plutil -extract ProgramArguments json -o - "$f"; echo
  plutil -extract WorkingDirectory raw -o - "$f" 2>/dev/null
done
```

다섯 개가 나와야 한다: `luma-api`, `luma-tunnel`, `token-service`, `luma-backup`, `lan-address`. 그다음:

```bash
OLD_GLASSES="$(plutil -extract WorkingDirectory raw -o - ~/Library/LaunchAgents/com.woolab.lumella.luma-tunnel.plist)"
OLD_SERVER="$(dirname "$(plutil -extract WorkingDirectory raw -o - ~/Library/LaunchAgents/com.woolab.lumella.luma-api.plist)")"
echo "안경 저장소: $OLD_GLASSES"; git -C "$OLD_GLASSES" remote get-url origin
echo "서버 저장소: $OLD_SERVER";  git -C "$OLD_SERVER"  remote get-url origin
[ -n "$OLD_GLASSES" ] && [ -n "$OLD_SERVER" ] && [ "$(dirname "$OLD_GLASSES")" = "$(dirname "$OLD_SERVER")" ] \
  && echo "나란히 있음 — 계속" || echo "못 찾았거나 나란히 있지 않음 — 멈추세요"
```

- 안경 저장소 주소는 `…/lumella.git`, 서버 저장소는 `…/luma.git`이어야 한다. 아니면 **멈추세요.**

## 2. 고친 게 없는지 보고 최신으로 받기

```bash
git -C "$OLD_GLASSES" status --short | grep -v '^??'
git -C "$OLD_SERVER"  status --short | grep -v '^??'
git -C "$OLD_SERVER"  branch --show-current
```

- 위 두 줄에서 **아무것도 안 나와야** 한다. 무언가 나오면 맥미니에서 고친 파일이 있는 것이다. **멈추세요.**
- 서버 저장소는 `main`이어야 한다.

```bash
git -C "$OLD_GLASSES" pull --ff-only
git -C "$OLD_SERVER"  pull --ff-only
git -C "$OLD_GLASSES" log --oneline -1    # 이 문서를 넣은 커밋이거나 그 뒤
git -C "$OLD_SERVER"  log --oneline -1    # f0ccd7f 이거나 그 뒤 (greenlet 수정 포함)
```

옛 주소(`10kH/lumella`, `10kH/luma`)는 GitHub이 새 주소로 넘겨 주므로 그대로 받아진다.

## 3. 백업 하나 떠 두기

데이터(`luma-api/data/`)는 폴더와 같이 옮겨지므로 사라지지 않는다. 그래도 옮기기 전에 하나 떠 둔다.

```bash
bash "$OLD_GLASSES/ops/launchd/manage.sh" backup-now
ls -t ~/Backups/luma | head -1      # 방금 시각의 파일이 맨 위
```

## 4. 서비스 멈추기 (여기서부터 안경이 KLLA 서버를 못 쓴다)

```bash
M="$OLD_GLASSES/ops/launchd/manage.sh"
"$M" tunnel-uninstall
"$M" luma-uninstall
"$M" uninstall
"$M" backup-uninstall
"$M" lan-uninstall
launchctl list | grep com.woolab.lumella      # 아무것도 안 나와야 함
```

## 5. 폴더 옮기고 git 주소 바꾸기

```bash
mkdir -p ~/workspace
{ [ -e ~/workspace/KLLA ] || [ -e ~/workspace/KLLA-server ]; } && echo "이미 있음 — 멈추세요" || echo "비어 있음 — 계속"
```

```bash
mv "$OLD_GLASSES" ~/workspace/KLLA
mv "$OLD_SERVER"  ~/workspace/KLLA-server

git -C ~/workspace/KLLA remote set-url origin \
  "$(git -C ~/workspace/KLLA remote get-url origin | sed -E 's#/lumella(\.git)?$#/KLLA.git#')"
git -C ~/workspace/KLLA-server remote set-url origin \
  "$(git -C ~/workspace/KLLA-server remote get-url origin | sed -E 's#/luma(\.git)?$#/KLLA-server.git#')"

git -C ~/workspace/KLLA remote get-url origin          # …/KLLA.git
git -C ~/workspace/KLLA-server remote get-url origin   # …/KLLA-server.git
git -C ~/workspace/KLLA fetch && git -C ~/workspace/KLLA-server fetch && echo "주소 OK"
```

비밀 파일(`KLLA-server/luma-api/.env`, `KLLA/token-service/.env.local`, `KLLA/local.properties`)과
`KLLA/.vercel/`(터널이 Vercel에 주소를 올릴 때 씀)은 폴더와 같이 옮겨졌다. 따로 할 일 없음.

## 6. 서버 가상환경 다시 만들기

가상환경 안에 옛 폴더 경로가 박혀 있어서, 그대로 켜면 `No module named 'luma_api'`로 죽는다.

```bash
cd ~/workspace/KLLA-server/luma-api
command -v uv || brew install uv
uv sync --reinstall
.venv/bin/python -c "import luma_api, greenlet; print('venv OK')"
```

- `venv OK`가 나와야 한다. greenlet이 없다고 나오면 2단계의 `pull`이 안 된 것이다(f0ccd7f에서 고쳤다).
- `--reinstall`은 lock 파일에 없는 패키지를 지운다. 손으로 깔아 둔 도구(luma-evolution, pytest 등)는 여기서 빠진다 — 서버 실행에는 필요 없다.

## 7. 서비스 다시 켜기 (새 위치에서)

```bash
cd ~/workspace/KLLA
M=ops/launchd/manage.sh
$M luma-install        # → OK: luma-api healthy on :8010
$M install             # → OK: http://127.0.0.1:8788/healthz is up
$M tunnel-install      # 터널 주소를 Vercel에 다시 올린다 (30초쯤 걸림)
$M backup-install
$M lan-install
```

## 8. 확인

```bash
cd ~/workspace/KLLA
M=ops/launchd/manage.sh
$M luma-status; $M status; $M tunnel-status; $M lan-status

# LaunchAgent 4개가 옛 폴더를 더 가리키지 않는지 — 아무것도 안 나오면 정상
grep -lF -e "$OLD_GLASSES" -e "$OLD_SERVER" ~/Library/LaunchAgents/com.woolab.lumella.*.plist
grep -c 'workspace/KLLA' ~/Library/LaunchAgents/com.woolab.lumella.*.plist   # 다섯 줄 모두 1 이상

# 백업 스크립트가 새 서버 폴더를 찾는지
$M backup-now && ls -t ~/Backups/luma | head -1

# 안경이 쓰는 길 그대로: Vercel → 터널 → KLLA 서버 (30초 기다린 뒤)
TOK=$(grep '^lumella.localToken=' local.properties | cut -d= -f2-)
URL=$(curl -s -H "X-Lumella-Local-Token: $TOK" https://lumella-token.vercel.app/v1/config \
      | ~/workspace/KLLA-server/luma-api/.venv/bin/python -c 'import sys, json; print(json.load(sys.stdin)["lumaBaseUrl"])')
echo "$URL"; curl -s "$URL/v1/capabilities"; echo     # "coach": true 가 보여야 함
```

마지막으로 안경을 쓰고 한 마디 걸어 본다. 튜터가 대답하고, 몇 턴 뒤 모서리의 코치 줄이 켜지면 끝.

## 9. 옛 경로가 남은 곳 찾기

```bash
grep -lF -e "$OLD_GLASSES" -e "$OLD_SERVER" ~/.zshrc ~/.zprofile ~/.bash_profile ~/Library/LaunchAgents/*.plist 2>/dev/null
crontab -l 2>/dev/null | grep -F -e "$OLD_GLASSES" -e "$OLD_SERVER"
ls "$(dirname "$OLD_GLASSES")"      # 옛 폴더가 있던 곳. 비었거나 다른 것만 남았는지 확인
```

- 무언가 나오면 경로를 `~/workspace/KLLA`, `~/workspace/KLLA-server`로 고친다.
- 옛 폴더가 있던 곳(예: `~/workspace/TUTOR/lumella`)에 다른 파일이 남아 있으면 지우지 말고 알려 줄 것.

---

## 문제가 생기면

**`luma-install`이 "not healthy"라고 할 때**

```bash
tail -30 ~/workspace/KLLA/tmp/token-service/luma-api.err.log
```

- `No module named …`: 6단계를 다시 한다. 그래도 같으면 맥미니에 손으로 깔아 두었던 패키지가 `uv sync`로 지워진 것이다.
  `cd ~/workspace/KLLA-server/luma-api && uv pip install <그 패키지>` 후 `$M luma-install`, 그리고 알려 줄 것(의존성에 넣어야 함).
- `address already in use`: `$M luma-install`을 한 번 더 (스크립트가 :8010을 비운다).

**터널 주소가 안 바뀔 때** — `$M tunnel-status`의 로그를 본다. Vercel 인증(`~/.config/lumella/tunnel.env`)은 이번 작업과 상관없다.

**전부 되돌리기**

```bash
cd ~/workspace/KLLA && M=ops/launchd/manage.sh
$M tunnel-uninstall; $M luma-uninstall; $M uninstall; $M backup-uninstall
mv ~/workspace/KLLA "$OLD_GLASSES"
mv ~/workspace/KLLA-server "$OLD_SERVER"
(cd "$OLD_SERVER/luma-api" && uv sync --reinstall)       # 가상환경을 옛 경로로 다시
cd "$OLD_GLASSES" && M=ops/launchd/manage.sh
$M luma-install && $M install && $M tunnel-install && $M backup-install
```

git 주소는 새 주소 그대로 둬도 된다(같은 저장소다).
