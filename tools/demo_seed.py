#!/usr/bin/env python3
"""
시연 데이터 생성 (에뮬레이터 pleos_connect_210 + 디버그 빌드).

DB에 직접 쓰지 않는다. Virtual Console 웹 API(기어·속도·누적거리)·GPS(adb emu geo fix)·에뮬레이터 시계로
실제 운행을 재현하고 앱이 스스로 기록하게 한다. 사람이 화면에서 하는 단계(설정 입력, 분류 확인·확정, 정정)만
디버그 빌드 전용 브로드캐스트(.debug.DemoSeedReceiver)로 대신하며, 이것도 화면과 같은 TripActions 경로를 탄다.

  python3 tools/demo_seed.py seed      # 앱 데이터 초기화(pm clear) → 운행 19건 재현. 끝나면 시계 자동 맞춤으로 되돌림
  python3 tools/demo_seed.py prepare   # 촬영 전: 시계 자동 맞춤 끄고 2026-10-20 18:00, 누적거리 마지막 값으로 맞춤
  python3 tools/demo_seed.py restore   # 촬영 후: 시계 자동 맞춤 켜기

전제: 디버그 APK 설치(./gradlew installDebug), 에뮬레이터 콜드 부팅 직후(누적거리 12,346 km 이하.
Virtual Console은 누적거리를 줄이지 못해 seed를 다시 돌리려면 에뮬레이터를 다시 콜드 부팅해야 함). 같은 명령을 다시 돌리면 같은 결과가 나온다
(시각은 분 단위로 고정, 확정·정정 시각도 시나리오 시계 기준).
"""
import base64
import datetime as dt
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
PKG = "app.bizdrivelog"
USER = "10"  # Pleos Connect 에뮬레이터의 사용자
CONSOLE = "http://127.0.0.1:8088"
KST = dt.timezone(dt.timedelta(hours=9))

START_ODOMETER = 12346.0
PROFILE = {
    "company": "(주)샘플상사",
    "bizNo": "000-00-00000",
    "carModel": "아이오닉 5",
    "plate": "12가3456",
    "dept": "영업팀",
    "userName": "홍길동",
}

# 위치 (위도, 경도). 자택·회사는 반경 300m 영역으로 등록된다. 방문처는 가상의 회사명
POINTS = {
    "자택": (37.5665, 126.9780),
    "회사": (37.5000, 127.0360),
    "가나전자": (37.4020, 127.1080),
    "다라물산": (37.4850, 126.8970),
    "마바유통": (37.2750, 127.0090),
    "사아테크": (37.3940, 126.9630),
    "교외": (37.6580, 127.2440),
    "마트": (37.5400, 127.0700),
}

C, B, P = "COMMUTE", "BUSINESS", "PERSONAL"

# (날짜, 출발, 도착, 출발지, 도착지, km, 구분, 목적, 방문처). 목적이 None이면 앱 제안값 그대로(출퇴근: 출근/퇴근)
# 마지막 1건은 미확정으로 남긴다 (영상에서 확인 흐름 시연)
TRIPS = [
    ("2026-10-12", "08:05", "08:41", "자택", "회사", 12.4, C, None, None),
    ("2026-10-12", "10:20", "10:58", "회사", "가나전자", 18.6, B, "거래처 방문", "가나전자"),
    ("2026-10-12", "15:30", "16:12", "가나전자", "회사", 18.7, B, "거래처 방문", "가나전자"),
    ("2026-10-12", "18:35", "19:14", "회사", "자택", 12.5, C, None, None),
    # 5번: 목적을 "납품·배송"으로 잘못 확정 → 같은 날 저녁 "거래처 미팅"으로 정정
    ("2026-10-14", "08:10", "09:02", "자택", "다라물산", 24.3, B, "납품·배송", "다라물산"),
    ("2026-10-14", "13:40", "14:25", "다라물산", "회사", 21.7, B, "거래처 미팅", "다라물산"),
    ("2026-10-14", "18:50", "19:30", "회사", "자택", 12.6, C, None, None),
    ("2026-10-16", "08:05", "08:42", "자택", "회사", 12.4, C, None, None),
    ("2026-10-16", "10:00", "10:55", "회사", "마바유통", 31.2, B, "납품·배송", "마바유통"),
    ("2026-10-16", "14:20", "15:18", "마바유통", "회사", 30.8, B, "납품·배송", "마바유통"),
    ("2026-10-16", "18:30", "19:08", "회사", "자택", 12.5, C, None, None),
    ("2026-10-17", "10:30", "11:35", "자택", "교외", 28.4, P, None, None),
    ("2026-10-18", "14:00", "14:30", "자택", "마트", 16.2, P, None, None),
    ("2026-10-19", "08:05", "08:40", "자택", "회사", 12.4, C, None, None),
    ("2026-10-19", "10:30", "11:20", "회사", "사아테크", 22.0, B, "교육·행사", "사아테크"),
    ("2026-10-19", "15:50", "16:38", "사아테크", "회사", 21.8, B, "교육·행사", "사아테크"),
    ("2026-10-19", "18:40", "19:20", "회사", "자택", 12.5, C, None, None),
    ("2026-10-20", "08:10", "08:47", "자택", "회사", 12.4, C, None, None),
    ("2026-10-20", "10:40", "11:15", "회사", "가나전자", 18.5, B, None, None),  # 미확정
]
CORRECTION = {"after_trip": 7, "trip_id": 5, "at": ("2026-10-14", "21:10"), "purpose": "거래처 미팅", "reason": "목적 입력 오류"}
SETUP_AT = ("2026-10-11", "20:00")
FILM_AT = ("2026-10-20", "18:00")
FINAL_ODOMETER = round(START_ODOMETER + sum(t[5] for t in TRIPS), 1)


# ---------- adb ----------

def adb(*args, check=True):
    r = subprocess.run([ADB, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip()


def shell(*args, check=True):
    return adb("shell", *args, check=check)


def at(day, hhmm):
    return dt.datetime.fromisoformat(f"{day}T{hhmm}:00").replace(tzinfo=KST)


def set_clock(when):
    shell("cmd", "alarm", "set-time", str(int(when.timestamp() * 1000)))


def auto_time(on):
    shell("settings", "put", "global", "auto_time", "1" if on else "0")


def geo(name):
    lat, lng = POINTS[name]
    adb("emu", "geo", "fix", str(lng), str(lat))  # 경도, 위도 순서


def b64(text):
    return base64.urlsafe_b64encode(text.encode()).decode()


def seed(op, *extras):
    shell("am", "broadcast", "--user", USER, "-n", f"{PKG}/.debug.DemoSeedReceiver", "--es", "op", op, *extras)
    return wait_log(f"seed {op} (ok|failed)")


def wait_log(pattern, timeout=20):
    """logcat에서 pattern이 나올 때까지 기다린다. 매 동작 전에 clear_log()로 비워 둔다."""
    import re
    rx = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        out = adb("logcat", "-d", "-v", "brief", "-s", "BizDriveLog", "BizDriveLogSeed")
        for line in out.splitlines():
            if rx.search(line):
                if "failed" in line:
                    sys.exit(f"app error: {line}")
                return line
        time.sleep(0.5)
    sys.exit(f"timeout waiting for log /{pattern}/")


def clear_log():
    adb("logcat", "-c", check=False)


# ---------- Virtual Console ----------

class Console:
    """Virtual Console HTTP API. 브라우저 세션 heartbeat가 없으면 503 emulator_inactive라 직접 보낸다."""

    def __init__(self):
        adb("forward", "tcp:8088", "tcp:8088")
        self._stop = threading.Event()
        self._beat()
        threading.Thread(target=self._loop, daemon=True).start()

    def _req(self, method, path):
        req = urllib.request.Request(CONSOLE + path, method=method, data=b"" if method != "GET" else None)
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.read().decode()

    def _beat(self):
        self._req("POST", "/browser/session?client=demo_seed")

    def _loop(self):
        while not self._stop.wait(1):
            try:
                self._beat()
            except (urllib.error.URLError, OSError):
                pass

    def put(self, path):
        for _ in range(5):
            try:
                return self._req("PUT", path)
            except urllib.error.HTTPError as e:
                body = e.read().decode(errors="replace")
                if "must_not_decrease" in body:
                    sys.exit("누적거리는 줄일 수 없음 (Virtual Console). 에뮬레이터를 콜드 부팅(누적거리 0)한 뒤 다시 실행")
                if e.code != 503:
                    sys.exit(f"console PUT {path} failed: {e.code} {body}")
                time.sleep(1)
        sys.exit(f"console PUT {path} failed (503)")

    def gear(self, g):
        self.put(f"/vehicle/gear?gear={g}")

    def speed(self, kmh):
        self.put(f"/vehicle/controls?speed={kmh}")

    def odometer(self, km):
        self.put(f"/vehicle/odometer?traveled_distance_km={km:.1f}")

    def close(self):
        self._stop.set()


# ---------- 시나리오 ----------

def drive(console, n, trip, odometer):
    day, t0, t1, src, dst, km, mode, purpose, visit = trip
    end_km = round(odometer + km, 1)
    geo(src)
    time.sleep(2)  # 영역 알림(ENTER/EXIT) 반영
    set_clock(at(day, t0))
    clear_log()
    console.gear("Drive")
    wait_log(r"trip started at")
    console.speed(50)
    geo(dst)
    console.odometer(end_km)
    wait_log(rf"odometer {end_km} km")
    console.speed(0)
    time.sleep(2)
    set_clock(at(day, t1))
    clear_log()
    console.gear("Park")
    line = wait_log(r"trip ended at")
    if f"suggest={mode}" not in line:
        sys.exit(f"trip {n}: expected {mode}, app suggested: {line}")
    print(f"  #{n:2d} {day} {t0}-{t1} {src}→{dst} {km} km  {mode}")
    return end_km


def confirm(trip):
    day, _, t1, _, _, _, mode, purpose, visit = trip
    set_clock(at(day, t1) + dt.timedelta(minutes=2))  # 정차 후 분류 카드에서 확정
    extras = ["--es", "mode", mode]
    if purpose is not None:
        extras += ["--es", "purpose", b64(purpose)]
    if visit is not None:
        extras += ["--es", "visit", b64(visit)]
    clear_log()
    print("     " + seed("confirm", *extras).split("ok ", 1)[-1])


def correct(c):
    set_clock(at(*c["at"]))
    clear_log()
    line = seed("correct", "--el", "tripId", str(c["trip_id"]), "--es", "purpose", b64(c["purpose"]), "--es", "reason", b64(c["reason"]))
    print("     정정 " + line.split("ok ", 1)[-1])


def reset_app(console):
    console.speed(0)
    console.gear("Park")
    shell("pm", "clear", "--user", USER, PKG)
    for perm in ("pleos.car.permission.FUSED_LOCATION", "android.permission.POST_NOTIFICATIONS"):
        shell("pm", "grant", "--user", USER, PKG, perm)
    console.odometer(START_ODOMETER)
    geo("자택")
    set_clock(at(*SETUP_AT))
    clear_log()
    shell("am", "start", "--user", USER, "-n", f"{PKG}/.ui.MainActivity")
    wait_log(rf"odometer {START_ODOMETER} km", timeout=40)
    # 음성 안내는 시나리오 동안 끈다 (정차마다 질문 → 무응답 대기). 끝나면 다시 켬
    clear_log()
    seed("voice", "--ez", "enabled", "false")
    clear_log()
    seed("profile", *[a for k, v in PROFILE.items() for a in ("--es", k, b64(v))])
    for kind, name in (("HOME", "자택"), ("OFFICE", "회사")):
        lat, lng = POINTS[name]
        clear_log()
        seed("place", "--es", "kind", kind, "--es", "lat", str(lat), "--es", "lng", str(lng))
    # 화면은 머리 정보를 시작할 때 읽으므로 앱을 다시 띄운다 (운행 중이 아니라 서비스 재시작도 문제없음)
    shell("am", "force-stop", "--user", USER, PKG)
    clear_log()
    shell("am", "start", "--user", USER, "-n", f"{PKG}/.ui.MainActivity")
    wait_log(r"fence registered OFFICE", timeout=40)
    wait_log(rf"odometer {START_ODOMETER} km", timeout=40)


def cmd_seed():
    if "DEBUGGABLE" not in shell("dumpsys", "package", PKG, check=False):
        sys.exit("debug APK not installed (./gradlew installDebug)")
    auto_time(False)
    shell("cmd", "alarm", "set-timezone", "Asia/Seoul", check=False)
    console = Console()
    try:
        print("앱 초기화·설정 입력")
        reset_app(console)
        odometer = START_ODOMETER
        print(f"운행 {len(TRIPS)}건 (마지막 1건 미확정)")
        for n, trip in enumerate(TRIPS, 1):
            odometer = drive(console, n, trip, odometer)
            if n < len(TRIPS):
                confirm(trip)
            if n == CORRECTION["after_trip"]:
                correct(CORRECTION)
        clear_log()
        seed("voice", "--ez", "enabled", "true")
        print(f"완료: 누적거리 {odometer} km")
        print(json.dumps(expected(), ensure_ascii=False))
    finally:
        console.close()
        auto_time(True)
        print("시계 자동 맞춤 다시 켬 (촬영 전에는 prepare)")


def expected():
    """시나리오 기준 기대값 (확정 운행만 집계, 정정 반영)"""
    done = TRIPS[:-1]
    total = round(sum(t[5] for t in done), 1)
    biz = round(sum(t[5] for t in done if t[6] != P), 1)
    return {
        "confirmed": len(done), "pending": 1, "pending_km": TRIPS[-1][5], "corrections": 1,
        "total_km": total, "business_use_km": biz, "ratio": f"{biz / total * 100:.1f}%",
        "commute_km": round(sum(t[5] for t in done if t[6] == C), 1),
        "business_km": round(sum(t[5] for t in done if t[6] == B), 1),
        "personal_km": round(sum(t[5] for t in done if t[6] == P), 1),
    }


def cmd_prepare():
    """콜드 부팅하면 누적거리가 0으로, 시계는 실제 시각으로 돌아간다. 촬영 전에 시나리오 마지막 상태로 맞춘다."""
    auto_time(False)
    set_clock(at(*FILM_AT))
    console = Console()
    try:
        console.speed(0)
        console.gear("Park")
        console.odometer(FINAL_ODOMETER)
    finally:
        console.close()
    print(f"시계 {FILM_AT[0]} {FILM_AT[1]}, 누적거리 {FINAL_ODOMETER} km. 촬영 후 restore")


def cmd_restore():
    auto_time(True)
    print("시계 자동 맞춤 켬")


if __name__ == "__main__":
    {"seed": cmd_seed, "prepare": cmd_prepare, "restore": cmd_restore, "expected": lambda: print(json.dumps(expected(), ensure_ascii=False, indent=1))}.get(
        sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
