#!/usr/bin/env python3
"""
성능 측정 (에뮬레이터 pleos_connect_210, 릴리스 또는 디버그 APK).

4구간을 차례로 잰다: 정차·앱 화면 3분 → 주행·앱 화면(주행 최소 화면) 5분 → 주행·백그라운드 3분 → 정차·백그라운드 3분.
주행은 Virtual Console로 속도 60 + 6초마다 누적거리 0.1 km. CPU는 /proc/<pid>/stat(utime+stime) 구간 차이,
6초마다 구간 최대를 함께 기록하고, PSS는 30초마다 dumpsys meminfo의 TOTAL PSS.

  python3 tools/perf_measure.py > perf.out

전제: 콜드 부팅 직후(누적거리 12,500 km 이하), 앱 설치·권한 부여, 자택·회사 등록, 음성 안내 켜짐, 데이터 1건 이하.
Virtual Console·adb는 demo_seed.py와 같은 것을 쓴다. 측정 중 앱 프로세스가 바뀌면(재시작) 멈춘다.
"""
import json
import os
import re
import sys
import time

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import demo_seed as d  # noqa: E402

HZ = 100        # 커널 클록 틱
CORES = 4       # 에뮬레이터 vCPU 수 (기기 전체 기준 환산)
START_ODOMETER = 12500.0


def pid():
    return d.shell("pidof", d.PKG, check=False).strip()


def cpu_ticks(p):
    s = d.shell("cat", f"/proc/{p}/stat", check=False)
    if ") " not in s:
        print("PROCESS GONE", p, time.strftime("%X"), flush=True)
        raise SystemExit(3)
    f = s.rsplit(") ", 1)[1].split()
    return int(f[11]) + int(f[12])


def pss_mb():
    m = re.search(r"TOTAL PSS:\s+(\d+)", d.shell("dumpsys", "meminfo", d.PKG, check=False))
    return int(m[1]) / 1024 if m else None


def measure(name, secs, tick=None):
    p = pid()
    t0 = time.time()
    c0 = cpu_ticks(p)
    last_c, last_t = c0, t0
    peaks, pss = [], []
    n = 0
    while time.time() - t0 < secs:
        time.sleep(6)
        n += 1
        if tick:
            tick()
        now = time.time()
        c = cpu_ticks(p)
        peaks.append((c - last_c) / HZ / (now - last_t) * 100)
        last_c, last_t = c, now
        if n % 5 == 0:
            pss.append(pss_mb())
    wall = time.time() - t0
    cpu = (cpu_ticks(p) - c0) / HZ / wall * 100
    print("  pid same:", pid() == p, flush=True)
    print(json.dumps(dict(
        name=name, secs=round(wall), cpu_core=round(cpu, 2), cpu_device=round(cpu / CORES, 2),
        peak_6s=round(max(peaks), 1), pss_avg=round(sum(pss) / len(pss), 1), pss_max=round(max(pss), 1),
    ), ensure_ascii=False), flush=True)


def main():
    c = d.Console()
    odometer = [START_ODOMETER]

    def tick():  # 60 km/h = 6초에 0.1 km
        odometer[0] = round(odometer[0] + 0.1, 1)
        c.odometer(odometer[0])

    try:
        c.speed(0)
        c.gear("Park")
        c.odometer(odometer[0])
        d.shell("am", "start", "--user", d.USER, "-n", f"{d.PKG}/.ui.MainActivity", check=False)
        time.sleep(30)
        measure("정차·앱 화면(홈)", 180)
        c.gear("Drive")
        c.speed(60)
        time.sleep(10)  # 시작 안내 TTS는 넣지 않음
        measure("주행·앱 화면(주행 최소 화면)", 300, tick)
        d.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(5)
        measure("주행·백그라운드", 180, tick)
        c.speed(0)
        c.gear("Park")
        time.sleep(20)  # 정차 음성 질문이 끝날 때까지
        measure("정차·백그라운드", 180)
    finally:
        c.close()


if __name__ == "__main__":
    main()
