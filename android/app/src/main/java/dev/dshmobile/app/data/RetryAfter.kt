package dev.dshmobile.app.data

internal fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long {
    if (value == null) return 0
    return value.toLongOrNull()?.coerceIn(0, 3600)?.times(1000)
        ?: runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - now }.getOrNull()?.coerceIn(0, 3600000) ?: 0
}
