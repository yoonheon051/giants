package com.example.lottelock

/** 선택할 수 있는 응원 팀: 네이버 팀 코드와 리그 분류 (빈 분류 = 야구 전체, 국제대회는 대회마다 분류가 달라서) */
enum class TeamChoice(val code: String, val category: String) {
    LOTTE("LT", "kbo"),
    KOREA("KR", ""),
}
