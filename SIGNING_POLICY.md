# Android Signing Policy

## 개인용 공개 빌드 기준

이 저장소는 개인 사용을 전제로 하며, 기존 설치본 위에 덮어쓰기 업데이트 호환성을 유지하지 않는다.

- Application ID: `com.personal.youtubescriptcopy.reinstall`
- versionCode: `14`
- versionName: `1.3.7`
- GitHub Actions가 Release 실행 때마다 임시 서명키를 생성한다.
- 서명키와 비밀번호를 Git 파일, Git 이력, Release 자산 또는 Repository Secrets에 보관하지 않는다.
- 공개 저장소 하나만으로 테스트, APK 빌드, 서명, GitHub Release 생성과 재검증까지 수행한다.

## 설치 방식

새 Release APK를 설치할 때 기존 설치본과 서명 인증서가 다를 수 있으므로, 기존 앱을 삭제한 뒤 새 APK를 설치한다.

앱 삭제 시 Android가 해당 앱의 로컬 데이터를 함께 제거할 수 있으므로 필요한 설정은 삭제 전에 별도로 보관한다.

## 검증

1. 단위 테스트를 통과해야 한다.
2. Release APK를 생성하고 `apksigner verify --verbose --print-certs`로 서명을 검증한다.
3. 패키지명, versionCode, versionName과 APK 무결성을 확인한다.
4. 생성한 APK, checksum, 소스 ZIP을 같은 공개 저장소의 GitHub Release에 게시한다.
5. 게시한 Release 자산을 다시 내려받아 checksum, 서명, 패키지 정보를 재검증한다.
6. 다른 저장소, 과거 비공개 Release 또는 외부 비밀 저장소를 빌드 의존성으로 사용하지 않는다.
