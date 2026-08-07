# Android Signing Policy

## 공개 저장소 기준선

이 저장소는 개인 사용을 전제로 한다.

- Application ID: `com.personal.youtubescriptcopy.reinstall`
- versionCode: `14`
- versionName: `1.3.7`
- 기존 비공개판과 첫 공개판 사이에는 서명키가 달라질 수 있으므로 한 번 삭제 후 재설치한다.
- 첫 공개판 이후에는 `YOUTUBE_PUBLIC_SIGNING_BUNDLE_BASE64`에 저장된 동일한 공개판 전용 서명키를 계속 사용한다.
- 따라서 이후 공개판끼리는 기존 앱 위에 덮어쓰기 업데이트할 수 있다.
- 서명키 원본은 Git 파일, Git 이력, 공개 Release 자산에 저장하지 않는다.

## 설치 방식

1. 비공개판을 사용 중이면 기존 앱을 삭제한다.
2. 공개 저장소의 기준 APK를 설치한다.
3. 그 이후 공개 저장소에서 만든 새 버전은 같은 서명키를 사용하므로 업데이트 설치한다.

앱 삭제 시 Android가 해당 앱의 로컬 데이터를 함께 제거할 수 있으므로 필요한 설정은 첫 공개판 전환 전에 별도로 보관한다.

## 검증

1. 단위 테스트를 통과해야 한다.
2. 공개판 전용 고정 서명키의 Certificate SHA-256은 `eb205a6db2ce0b8aa5bd5804069d1a5c62be1a9fb5140a655737384f195f4d8e`이다.
3. Release APK를 `apksigner verify --verbose --print-certs`로 검증하고 위 인증서와 일치해야 한다.
4. 패키지명, versionCode, versionName, APK 무결성을 확인한다.
5. 생성한 APK, checksum, 소스 ZIP을 같은 공개 저장소의 GitHub Release에 게시한 뒤 다시 내려받아 검증한다.
6. 다른 저장소나 과거 비공개 Release를 빌드 의존성으로 사용하지 않는다.
