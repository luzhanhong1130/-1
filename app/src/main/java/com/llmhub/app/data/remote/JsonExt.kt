package com.llmhub.app.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON 取值助手：兼容字段缺失 / 类型不符的情况，避免协议差异导致崩溃。
 * 各家 LLM 响应结构差异较大且字段名常变，统一走「容错读取」。
 */
internal fun JsonObject?.stringOrNull(key: String): String? =
    this?.get(key)?.let { element ->
        if (element is JsonNull) null
        else runCatching { element.jsonPrimitive.content }.getOrNull()
    }

internal fun JsonObject?.intOrNull(key: String): Int? =
    this?.get(key)?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

internal fun JsonObject?.objectOrNull(key: String): JsonObject? =
    this?.get(key)?.let { runCatching { it as JsonObject }.getOrNull() }

internal fun JsonObject?.arrayOrNull(key: String): JsonArray? =
    this?.get(key)?.let { runCatching { it as JsonArray }.getOrNull() }
