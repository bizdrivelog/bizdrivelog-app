# 비즈드라이브로그 (BizDriveLog)

업무용 승용차 운행기록부를 자동으로 작성하는 Pleos Connect 차량용 앱입니다.
시동을 걸고(기어 D) 다시 주차(P)하면 계기판 누적거리로 운행 한 건을 기록하고, 자택·회사 위치와 요일로 출퇴근·업무·개인을 제안합니다.
운전자는 정차 후 음성이나 화면으로 분류·목적만 확인하면 되고, 결산 때는 국세청 별지 서식(업무용승용차 운행기록부) 그대로 엑셀 파일을 휴대폰으로 받을 수 있습니다.

## 주요 기능

- **운행 자동 기록**: 기어 P → D/R/N이면 운행 시작, 다시 P면 종료. 시작·종료 시점 계기판 누적거리(km)를 저장하고 차이로 주행거리를 계산. 주행거리 0.1 km 미만, 또는 1분 미만이면서 0.3 km 미만인 주차 조작은 운행으로 남기지 않음. 앱이 운행 중 종료돼도 다시 시작되면 같은 운행으로 이어서 기록
- **3모드 분류 제안**: 평일 자택↔회사는 출퇴근(목적 "출근"/"퇴근" 자동), 그 밖의 평일 구간은 업무, 주말·공휴일은 개인
- **목적 입력**: 정차 후 음성 질문 → 답을 규칙으로 해석해 분류 카드에 채움. 화면에서는 목적 빠른 선택(거래처 방문·미팅·회의·납품·출장·교육)과 방문처 입력
- **기록 잠금과 정정 이력**: 확정한 운행은 DB 트리거로 수정·삭제가 막히고, 바꿀 내용은 사유와 함께 정정 이력으로만 추가(원본 보존). 집계·엑셀은 정정 반영값 사용
- **개인정보 보호**: 개인 운행은 출발·도착 좌표와 장소를 저장하지 않고 거리만 기록. 출발 위치는 운행 중 메모리에만 둠
- **월 요약**: 총 주행거리, 업무용 사용거리(출퇴근+업무), 업무 사용 비율. 확정 운행만 집계
- **엑셀 내보내기**: 국세청 별지 서식과 같은 칸 배치(시트 1 운행기록부, 시트 2 정정 이력). 기간은 이번 달·지난 달·과세기간. 날짜를 넘긴 운행은 출발 일시 기준으로 집계
- **휴대폰으로 받기**: 차량이 휴대폰 핫스팟에 연결돼 있으면 앱 안 일회용 HTTP 서버 + QR로 파일 전달 (외부 서버 없음, 5분 만료, 한 번 받으면 종료)
- **주행 중 화면**: 주행 규제(CarUxRestrictions) 중에는 "운행 중"과 이번 운행 거리만 표시
- **Gleo 연동**: 음성 비서 호출용 `assets/schema.json` (운행 분류·목적 입력, 업무 사용 비율 조회, 다운로드 QR 표시)

## 사용한 SDK·API

| 구분 | 사용 내용 |
|---|---|
| Pleos `Vehicle` 2.3.2 | Odometer `getCurrentDistanceDriven`·`registerDistanceDriven` (권한 `CAR_DRIVE`) |
| Pleos `FusedLocation` 1.1.3 | `getLastKnownLocation`, `addProximityAlert`(자택·회사 반경 300m 진입·이탈) |
| Pleos `SpeechToText` 3.3.1 | ON_DEVICE 음성 인식 (정차 후 목적 답변) |
| Pleos `TextToSpeech` 3.3.1 | 운행 시작 안내, 정차 후 질문 |
| Pleos Gleo Interface | `assets/schema.json`, `APP_ACTION_RESPONSE` 응답 |
| AAOS `CarPropertyManager` | `GEAR_SELECTION` 조회·구독 (권한 `CAR_POWERTRAIN`) |
| AAOS `CarUxRestrictionsManager` | 주행 규제 시 최소 화면 (`distractionOptimized` 선언) |
| 그 밖의 라이브러리 | Jetpack Compose, Room, fastexcel 0.18.4(xlsx), NanoHTTPD 2.3.1, qrcodegen 1.8.0 |

## 실행 방법

1. Android Studio에서 SDK Manager로 **Pleos Connect v2.1.0 시스템 이미지**(`system-images;android-34;pleos_car_emulator;arm64-v8a`)를 설치하고 AVD를 만듭니다.
2. 에뮬레이터를 실행합니다: `$ANDROID_HOME/emulator/emulator -avd <AVD 이름>`
3. 빌드·설치합니다.
   ```sh
   ./gradlew installDebug          # 또는 apk/ 의 릴리스 APK를 adb install
   adb shell pm grant --user 10 app.bizdrivelog pleos.car.permission.FUSED_LOCATION
   adb shell pm grant --user 10 app.bizdrivelog android.permission.POST_NOTIFICATIONS
   ```
   Pleos Connect 에뮬레이터의 사용자는 user 10이라 `--user 10`이 필요합니다.
4. **Virtual Console**로 차량 상태를 바꿉니다: `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`.
   기어(P/D), 속도, 누적거리(`traveled_distance_km`)를 바꾸면 앱이 운행을 기록합니다. 위치는 `adb emu geo fix <경도> <위도>`.
5. 앱 설정에서 운행기록부 머리 정보(상호·사업자등록번호·차종·차량번호·부서·성명)와 자택·회사 위치("현재 위치로 등록")를 입력합니다.

## 테스트 방법

- 단위 테스트: `./gradlew testDebugUnitTest` (분류·운행 종료 처리·음성 답변 해석·집계·정정 표시)
- 시연 데이터: `python3 tools/demo_seed.py seed`
  - 디버그 빌드 + 에뮬레이터 콜드 부팅 직후(누적거리 0)에 실행합니다. Virtual Console은 누적거리를 줄일 수 없어 다시 돌리려면 다시 콜드 부팅합니다.
  - 앱 데이터를 초기화한 뒤 Virtual Console API·GPS·에뮬레이터 시계로 2026년 10월 운행 19건(출퇴근·업무·주말 개인·정정 1건·미확정 1건)을 재현하고, 앱이 스스로 기록합니다. DB에 직접 쓰지 않습니다.
  - 사람이 화면에서 하는 단계(설정 입력, 분류 확정, 정정)만 디버그 빌드 전용 브로드캐스트가 대신하며, 화면과 같은 처리 경로를 탑니다. 이 코드는 `src/debug`에만 있어 릴리스 APK에는 포함되지 않습니다.
  - 기대 결과: 확정 18건, 총 333.4 km, 업무용 288.8 km, 업무 사용 비율 86.6%
  - 촬영 전 `prepare`(시계·누적거리 맞춤), 끝나면 `restore`(시계 자동 맞춤)

## APK

- 릴리스 APK: [`apk/BizDriveLog_비즈드라이브로그_release.apk`](apk/) (저장소를 받으면 함께 받아집니다)
- 서명 설정: `~/.bizdrivelog-keys/keystore.properties`(또는 환경변수 `BIZDRIVELOG_KEYSTORE_PROPS`)가 있으면 그 키로 서명하고, 없으면 서명 없이 빌드됩니다. 키스토어는 저장소에 넣지 않습니다.

## 알려진 제약

- Pleos 개발자 콘솔에서 "Coming Soon"으로 표시된 SDK(차량·위치·음성)는 **Pleos Connect v2.1.0 에뮬레이터에서 검증한 결과 기준**입니다. 실차에서의 권한·동작은 확인하지 못했습니다.
- Gleo 연동 `schema.json`은 현대자동차그룹의 검토·승인 대상입니다. 에뮬레이터에서는 Gleo의 LLM 서버가 연결되지 않아 실제 음성 호출은 확인하지 못했고, 같은 인텐트를 adb로 보내 응답 흐름만 확인했습니다.
- 주행 중 화면(`distractionOptimized`)은 에뮬레이터에서 일반 앱 선언만으로 동작했지만, 실차·스토어 설치에서 별도 승인이 필요한지는 확인하지 못했습니다.
- 에뮬레이터에는 Wi-Fi가 없어 릴리스 빌드에서는 "휴대폰으로 받기"가 핫스팟 연결 안내로 끝납니다. QR 다운로드는 디버그 빌드의 주소 덮어쓰기로 검증했습니다.
- 기록은 차량 안에만 저장됩니다. 차량 데이터 초기화나 앱 삭제에 대비해 정기적으로 엑셀을 휴대폰으로 받아 두는 것을 권장합니다(백업 기능은 향후 과제).
- 앱을 설정에서 강제 종료하면 다시 열 때까지 기록이 멈춥니다. 다시 시작되면 저장된 마지막 상태와 비교해 운행을 닫고, 그 사이 늘어난 계기판 구간은 "기록 공백" 운행으로 남깁니다(시각은 정확하지 않고 분류는 사용자가 확인). 꺼진 동안 끝난 운행과 이어진 다음 운행을 주행 중에 복귀하면 한 건으로 합쳐집니다.
- 안드로이드 규칙상 백그라운드에서는 포그라운드 서비스를 시작할 수 없습니다. 부팅 완료·앱 업데이트·앱 화면 진입에서만 시작하며, 거부되면 알림으로 앱을 열도록 안내합니다.
- 공휴일 목록은 2026년 기준으로 코드에 들어 있어 해마다 갱신해야 합니다.
- 정식 출시 전에는 DB 스키마 변경 시 Migration을 갖춰야 합니다(현재 개발용 초기화 포함).
