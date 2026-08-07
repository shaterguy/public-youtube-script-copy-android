package com.personal.youtubescriptcopy;

/** Prompts Gemini as a transcription engine rather than a summarization assistant. */
final class GeminiPrompt {
    private GeminiPrompt() {
    }

    static String initial() {
        return "당신은 영상 전용 정밀 전사 엔진이다. 이 YouTube 영상의 음성을 직접 듣고 " +
                "처음 들리는 발화부터 마지막 발화까지 전부 전사하라.\n\n" +
                "반드시 지킬 규칙:\n" +
                "1. 요약, 번역, 의역, 문장 다듬기, 내용 재구성, 해설을 하지 않는다.\n" +
                "2. 영상에서 실제로 들리는 언어 그대로 기록한다. 반복 발화, 말더듬, 머뭇거림, " +
                "감탄사, 사례, 인용, 광고, 인트로와 아웃트로도 생략하지 않는다.\n" +
                "3. 들리지 않는 내용을 추측하거나 새로 만들지 않는다. 알아들을 수 없는 부분은 " +
                "[불명확]으로 표시한다. 의미 있는 웃음·박수·음악 등은 [웃음], [박수], [음악]처럼 표시한다.\n" +
                "4. 화자가 바뀔 때마다 줄을 바꾼다. 이름이 확실하면 이름을 쓰고, 확실하지 않으면 " +
                "화자 1, 화자 2처럼 일관되게 구분한다.\n" +
                "5. 각 화자 발화 줄을 정확히 '[HH:MM:SS] 화자: 발화' 형식으로 출력한다. " +
                "한 화자의 긴 발화에도 최소 30초마다 새 타임스탬프를 넣는다.\n" +
                "6. 제목, URL, 영상 길이, 요약, 목차, 안내 문장, Markdown 코드 블록은 출력하지 않는다. " +
                "전사 본문만 출력한다.\n" +
                "7. 출력 한도에 가까워지면 마지막 문장과 발화 줄을 완성한 뒤, 마지막 줄에만 " +
                "'[[CONTINUE_FROM=HH:MM:SS]]'를 출력한다. 아직 영상이 남았는데 임의로 끝내지 않는다.\n" +
                "8. 영상의 마지막 실제 발화까지 확인하고 전사를 모두 마쳤을 때만 마지막 줄에 " +
                "'[[TRANSCRIPT_COMPLETE=HH:MM:SS]]'를 출력한다.";
    }

    static String continuation(String lastTimestamp) {
        String position = lastTimestamp == null || lastTimestamp.isEmpty()
                ? "직전 응답의 마지막 완성 발화" : lastTimestamp;
        return "이 입력 영상은 원본 영상의 " + position + " 직전부터 잘라낸 이어받기 구간이다. " +
                "출력 타임스탬프는 잘라낸 구간의 00:00부터 다시 시작하지 말고 반드시 원본 영상의 " +
                "절대 시간으로 표시하라. " + position + " 다음에 실제로 이어지는 발화부터 영상 " +
                "끝까지 계속 전사하라. 직전 응답의 문장이나 타임스탬프를 반복하지 않는다. " +
                "처음 요청의 전사 규칙과 " +
                "'[HH:MM:SS] 화자: 발화' 형식을 그대로 지킨다. 설명이나 머리말 없이 이어지는 " +
                "전사 본문만 출력한다. 이어질 발화가 없으면 " +
                "'[[TRANSCRIPT_COMPLETE=HH:MM:SS]]'만 출력한다. 다시 출력 한도에 가까워지면 " +
                "마지막 완성 발화 뒤에만 '[[CONTINUE_FROM=HH:MM:SS]]'를 출력한다.";
    }
}
