# YouTube Script Copy 1.2.1

- 누적 전사의 마지막 타임스탬프와 영상 길이를 비교하는 완료 판정을 추가했습니다.
- 영상 끝 허용 범위는 최소 30초, 영상 길이의 3%, 최대 90초입니다.
- 영상 끝에 가까우면 CONTINUE_FROM 또는 MAX_TOKENS가 있어도 완료하여 불필요한 이어받기를 줄입니다.
- 영상 끝에서 멀면 STOP이어도 이어서 전사하여 중간 종료를 방지합니다.
- 설치 확인된 패키지 `com.personal.youtubescriptcopy.reinstall`을 정식 패키지로 확정하고 versionCode를 4로 올렸습니다.
- 1.2.1부터 보존되는 정식 릴리스 키로 서명합니다.
