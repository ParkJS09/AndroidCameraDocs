# NeuCam2 — Camera2 풀기능 카메라

순수 **Camera2 API** (CameraX 미사용) + Jetpack Compose. `compileSdk/targetSdk 37`, `minSdk 31`.

## 탭
| 탭 | 내용 |
|---|---|
| 카메라 | 사진 / 동영상 / 프로(수동) / 확장(Night·HDR·Bokeh·Face Retouch·Auto). 탭 포커스·측광, 핀치 줌, 그리드, 실시간 HUD |
| 멀티캠 | 논리 카메라의 물리 스트림 동시 출력(`setPhysicalCameraId`) / `getConcurrentCameraIds` 동시 카메라. 분할·PiP, 동시 촬영 |
| 옵션 | 기기의 `availableCaptureRequestKeys` **전부**를 타입별 편집기로 반복 요청에 오버라이드 (리플렉션으로 enum 이름 자동 해석, 트리거 키는 단발 전송, 요청 템플릿 전환) |
| 정보 | 모든 `CameraCharacteristics`, 스트림 구성, 확장, 10-bit/색공간, 동시 카메라 조합. 텍스트 공유 |

## 구성
- `camera/CameraController.kt` — 단일 카메라 파이프라인. REGULAR / HIGH_SPEED(120~960fps) / Extension 세션, JPEG·HEIC·Ultra HDR(JPEG_R)·RAW(DNG)·RAW+JPEG, MediaRecorder + persistent surface, 10-bit HDR(HLG/HDR10/HDR10+), 안정화, stream use case, `CameraDeviceSetup` 세션 지원 질의, 스트림 조합 실패 시 폴백
- `camera/MultiCameraController.kt` — 멀티 카메라
- `camera/Meta.kt`, `RequestKeySpecs.kt` — 메타데이터 리플렉션 & 옵션 편집기 생성
- `camera/PreviewTransform.kt` — TextureView 변환(센서 방향·디스플레이 회전·fill/fit), 탭 좌표→센서 좌표, JPEG 방향
- `ui/AppLayout.kt` — 폴더블 자세(Tabletop/Book), 가로/세로, 탭별 레이아웃 계산
- `ui/kit/` — 다크 카메라 UI 킷 (`Cam*` 컴포넌트)

## 디자인
카메라 앱 표준인 **다크 카메라 UI** (시스템 테마와 무관하게 항상 다크).
- 검정 배경 → 시선이 프리뷰에만 가고 노출/색 판단이 쉬움
- 프리뷰 위 컨트롤은 반투명 검정 + 얇은 외곽선 + 흰 아이콘 → 어떤 장면에서도 가독성 확보 (SurfaceView 위라 블러 대신 스크림)
- 강조색은 앰버 하나 (선택 모드·줌·수동값), 빨강 = 녹화, 초록 = 초점 고정
- 표준 셔터(흰 링 + 원 / 동영상 빨간 원 → 녹화 중 둥근 사각형), 실시간 수치는 모노스페이스

## 프리뷰
프리뷰는 **SurfaceView** — 회전은 합성기가 처리하고, 앱은 `setFixedSize` 로 버퍼 크기만 맞춘다.
- **전체**: 창 비율에 가장 가까운 버퍼를 골라 디바이스 전체 화면을 center-crop
- **16:9**: 16:9 버퍼를 콘텐츠 비율 그대로 박스에 맞춤

## Android 17 (API 37) 대응
- 대화면 방향/리사이즈 제한 무시 → 모든 창 크기·방향에서 동작, `configChanges` 로 회전/폴딩 시 세션 유지, 0↔180° 회전은 `DisplayListener` 로 처리
- Edge-to-edge, 예측형 뒤로가기
- `INFO_DEVICE_TYPE`(내장/외장/가상), `AvailabilityCallback.onCameraRemoved`, `ExtensionSessionConfiguration.setSessionWideParams`, `CameraExtensionCharacteristics.isExtensionSupported`, `LOGICAL_MULTI_CAMERA_ADDITIONAL_RESULTS`, SMPTE 2094-50 다이나믹 레인지 프로파일(리플렉션 자동 표시)
- API 36: `COLOR_CORRECTION_MODE_CCT`(켈빈 WB), `CONTROL_AE_PRIORITY_MODE` 등은 옵션 탭에서 자동 노출

## 빌드
```
./gradlew :app:installDebug
```
AGP 9.3.1 / Gradle 9.5 / Kotlin 2.4 / JDK 17+
