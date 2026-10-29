package com.animes

/**
 * JVM-only source selection policy. Keeps source classification separate from Android/network code.
 */
internal data class AbyssSourceOption(
    val label: String,
    val resId: Int,
    val size: Long,
    val partSize: Long,
    val status: Boolean,
    val path: String,
    val url: String,
    val keys: List<String> = emptyList(),
    val directUrl: String = "",
    val sub: String = ""
)

internal data class AbyssSourceSelectionResult(
    val selected: AbyssSourceOption?,
    val validCount: Int,
    val failureCode: String? = null,
    val failureMessage: String? = null
)

internal object AbyssSourceSelection {
    fun select(options: List<AbyssSourceOption>, requestedQuality: String): AbyssSourceSelectionResult {
        val valid = options.filter(::hasUsableLocation)
        if (valid.isEmpty()) {
            val onlyFragmented = options.isNotEmpty() && options.all {
                hasUsableLocation(it) && it.partSize > 0L && it.partSize < it.size
            }
            return if (onlyFragmented) {
                AbyssSourceSelectionResult(
                    selected = null,
                    validCount = 0,
                    failureCode = "unsupported",
                    failureMessage = "Este contenido solo está disponible en un formato fragmentado que todavía no se soporta / This content is only available in a split format that is not supported yet"
                )
            } else {
                AbyssSourceSelectionResult(null, 0, failureCode = "unavailable")
            }
        }

        val requested = when (requestedQuality.trim().lowercase()) {
            "3", "480", "480p" -> 3
            "4", "720", "720p" -> 4
            "5", "1080", "1080p" -> 5
            "best" -> Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }
        val chosenResId = if (requested == Int.MAX_VALUE) {
            valid.maxOf { it.resId }
        } else {
            valid.filter { it.resId == requested }.maxByOrNull { rank(it) }?.resId
                ?: valid.filter { it.resId < requested }.maxOfOrNull { it.resId }
                ?: valid.minOf { it.resId }
        }
        val selected = valid.asSequence()
            .filter { it.resId == chosenResId }
            .sortedWith(
                compareBy<AbyssSourceOption> { isFragmented(it) }
                    .thenByDescending { it.status }
                    .thenByDescending { it.size }
            )
            .firstOrNull()
        return AbyssSourceSelectionResult(selected, valid.size)
    }

    fun diagnosticSummary(options: List<AbyssSourceOption>, result: AbyssSourceSelectionResult): String {
        val perSource = options.mapIndexed { index, option ->
            val reason = when {
                !hasUsableLocation(option) -> "sin URL reproducible (missing playable URL/path)"
                result.selected === option && isFragmented(option) -> "elegida para validar URL fragmentada (selected for fragmented URL probe)"
                result.selected === option -> "elegida (selected)"
                isFragmented(option) -> "fragmentada (fragmented)"
                else -> "no es la calidad pedida (not requested quality)"
            }
            "#$index label=${option.label} res_id=${option.resId} size=${option.size} partSize=${option.partSize} reason=$reason keys=${option.keys.joinToString(",")}"
        }.joinToString(" || ")
        return "sourceCountBefore=${options.size} sourceCountAfter=${result.validCount}; $perSource"
    }

    private fun isFragmented(option: AbyssSourceOption): Boolean =
        option.partSize > 0L && option.partSize < option.size

    private fun hasUsableLocation(option: AbyssSourceOption): Boolean =
        option.directUrl.startsWith("https://") ||
            (option.path.isNotBlank() && option.url.startsWith("https://"))

    private fun rank(option: AbyssSourceOption): Long =
        (if (option.status) 1L shl 62 else 0L) + option.size.coerceAtLeast(0L).coerceAtMost((1L shl 62) - 1)
}
