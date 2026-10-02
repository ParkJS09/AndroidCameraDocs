# 옵션/정보 탭은 리플렉션으로 Camera2 메타데이터 키·상수 이름을 읽는다 (프레임워크 클래스라 R8 대상은 아니지만 명시).
-keep class android.hardware.camera2.** { *; }
