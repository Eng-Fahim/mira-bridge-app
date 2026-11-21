package com.example.uhf.ui.inventory

import java.util.Locale

data class ParsedOpticalCode(
    val rawPayload: String,
    val primaryCode: String,
    val gtin: String? = null,
    val serial: String? = null,
    val candidateKeys: List<String> = emptyList()
)

object MiraOpticalCodeParser {

    // رمز الفاصل القياسي GS1 FNC1 (ASCII 29)
    private const val GS = '\u001D'

    fun parse(rawInput: String): ParsedOpticalCode {
        val cleaned = rawInput.trim().removePrefix("]d2").removePrefix("]Q3")
        if (cleaned.isEmpty()) {
            return ParsedOpticalCode(rawPayload = rawInput, primaryCode = "")
        }

        val candidates = linkedSetOf<String>()
        var extractedGtin: String? = null
        var extractedSerial: String? = null

        // 1. روابط MIRA QR أو IBREG (مثل https://miratechye.com/v/MIRA-2026-0015?gtin=07...)
        if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            val withoutQuery = cleaned.substringBefore("?").trimEnd('/')
            val lastSegment = withoutQuery.substringAfterLast('/')
            if (lastSegment.isNotBlank() && lastSegment.length >= 4) {
                extractedSerial = lastSegment
                candidates.add(lastSegment)
                candidates.add(lastSegment.uppercase(Locale.US))
            }

            val query = cleaned.substringAfter("?", "")
            if (query.isNotEmpty()) {
                query.split("&").forEach { param ->
                    val key = param.substringBefore("=").lowercase(Locale.US)
                    val value = param.substringAfter("=", "").trim()
                    if (value.isNotEmpty() && key in setOf("id", "serial", "mira_id", "epc", "gtin", "code")) {
                        candidates.add(value)
                        candidates.add(value.uppercase(Locale.US))
                        if (key == "gtin") extractedGtin = value
                        if (key in setOf("serial", "mira_id", "id")) extractedSerial = value
                    }
                }
            }
        }

        // 2. صيغة GS1 بأقواس مقروءة: (01)00712345678901(21)SN12345
        val bracketRegex = Regex("""\((\d{2,4})\)([^\(]+)""")
        val bracketMatches = bracketRegex.findAll(cleaned).toList()
        if (bracketMatches.isNotEmpty()) {
            for (m in bracketMatches) {
                val ai = m.groupValues[1]
                val value = m.groupValues[2].replace(GS.toString(), "").trim()
                if (value.isEmpty()) continue
                when (ai) {
                    "01", "02" -> {
                        extractedGtin = value
                        candidates.add(value)
                        // تحويل GTIN-14 يبدأ بـ 0 إلى EAN-13
                        if (value.length == 14 && value.startsWith("0")) {
                            candidates.add(value.substring(1))
                        }
                    }
                    "21", "10" -> {
                        extractedSerial = value
                        candidates.add(value)
                        candidates.add(value.uppercase(Locale.US))
                    }
                }
            }
        } else if (cleaned.startsWith("01") && cleaned.length >= 16 && cleaned.substring(2, 16).all { it.isDigit() }) {
            // 3. صيغة GS1 خام بدون أقواس: 01 + 14 رقم GTIN + 21 + Serial
            val gtin14 = cleaned.substring(2, 16)
            extractedGtin = gtin14
            candidates.add(gtin14)
            if (gtin14.startsWith("0")) {
                candidates.add(gtin14.substring(1))
            }

            val rest = cleaned.substring(16).trimStart(GS)
            if (rest.startsWith("21") && rest.length > 2) {
                val serialPart = rest.substring(2).substringBefore(GS).trim()
                if (serialPart.isNotEmpty()) {
                    extractedSerial = serialPart
                    candidates.add(serialPart)
                    candidates.add(serialPart.uppercase(Locale.US))
                }
            }
        }

        // 4. إضافة النص الخام كخيار احتياطي
        candidates.add(cleaned)
        candidates.add(cleaned.uppercase(Locale.US))

        val primary = extractedSerial ?: extractedGtin ?: cleaned
        return ParsedOpticalCode(
            rawPayload = cleaned,
            primaryCode = primary,
            gtin = extractedGtin,
            serial = extractedSerial,
            candidateKeys = candidates.toList()
        )
    }
}
