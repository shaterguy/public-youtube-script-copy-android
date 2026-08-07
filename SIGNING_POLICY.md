# Android Signing Policy

## 독립 공개 저장소 기준

이 저장소의 정식 APK 빌드와 GitHub Release는 다른 저장소의 Git 이력, Release 자산 또는 파일을 참조하지 않는다.

정식 서명은 이 저장소의 GitHub Actions repository secrets만 사용한다.

- `YTSC_KEYSTORE_BASE64`: 기존 정식 Android 서명 키스토어 바이너리의 Base64
- `YTSC_KEYSTORE_PASSWORD`: 키스토어 비밀번호
- `YTSC_KEY_ALIAS`: 서명 키 alias
- `YTSC_KEY_PASSWORD`: 개인키 비밀번호

## 필수 규칙

1. 기존 설치본의 덮어쓰기 업데이트 호환성을 유지하기 위해 기존 정식 릴리스와 동일한 개인키와 인증서를 사용한다.
2. 키스토어 원본, Base64 원문, 비밀번호를 Git 파일·Git 이력·공개 Release 자산에 저장하지 않는다.
3. Pull Request 검증은 `android-ci.yml`에서 비밀정보 없이 단위 테스트와 unsigned APK 빌드로 수행한다.
4. 정식 Release는 `publish-v1.3.7.yml`에서 repository secrets를 복원해 서명한다.
5. 서명 APK는 `apksigner verify --verbose --print-certs`로 검증하고 패키지명·versionCode·versionName을 다시 읽어 확인한다.
6. 공개 저장소 내부의 이전 Release나 다른 저장소의 Release를 서명 자격증명의 저장소로 사용하지 않는다.

## 현재 기준

- Application ID: `com.personal.youtubescriptcopy.reinstall`
- versionCode: `14`
- versionName: `1.3.7`
- 지원 기준: 기존 정식 서명 신원 유지
