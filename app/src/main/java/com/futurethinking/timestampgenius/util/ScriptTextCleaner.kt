package com.futurethinking.timestampgenius.util

object ScriptTextCleaner {
    private val leadingReference = Regex(
        """^\s*[\[\(]?\s*[\d०-९]{1,4}\s*[\]\)]?\s*[\.:\)\-]\s*"""
    )
    private val leadingNumber = Regex(
        """^\s*[\d०-९]{1,4}\s+"""
    )

    fun clean(value: String): String {
        var text = value.replace(' ', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        text = text.replaceFirst(leadingReference, "")
        text = text.replaceFirst(leadingNumber, "")

        return text.trim()
    }
}
