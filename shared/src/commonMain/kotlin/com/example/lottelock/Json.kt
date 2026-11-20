package com.example.lottelock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/*
 * 네이버 응답과 저장해 둔 경기(composite)를 읽는 도우미.
 * 예전 org.json(안드로이드 전용)의 optString/optInt처럼, 값이 없거나 형식이 다르면 기본값을 돌려준다.
 */

fun parseJsonObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

fun parseJsonArray(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 문자열(숫자·불리언도 글자 그대로). 없거나 null이면 [default] */
fun JsonObject.str(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: default

/** 숫자 또는 숫자 문자열. 없거나 숫자가 아니면 [default] */
fun JsonObject.int(key: String, default: Int = 0): Int = intOrNull(key) ?: default

/** 숫자 또는 숫자 문자열만 인정. "-", "X", "", null 등은 null */
fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.toIntOrNull()

/** true/false 또는 "true"/"false". 없거나 다른 값이면 [default] */
fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull ?: default

/** 배열의 [i]번째 값을 숫자로. 범위 밖이거나 숫자가 아니면 null (진행 안 한 이닝) */
fun JsonArray.intAt(i: Int): Int? = (getOrNull(i) as? JsonPrimitive)?.toIntOrNull()

private fun JsonPrimitive.toIntOrNull(): Int? = when {
    this is JsonNull -> null
    isString -> content.trim().toIntOrNull()
    else -> content.toDoubleOrNull()?.toInt()   // 숫자 (true/false는 여기서 null)
}
