package com.haikson.aivoiceboard

// Analogous to FormatText() in the AHK script.
// Inserts a newline after each sentence-ending punctuation mark.
object TextFormatter {

    private val sentenceEnd = Regex("""([.!?])\s+""")

    fun format(text: String, enabled: Boolean): String {
        if (!enabled) return text
        return sentenceEnd.replace(text.trim()) { match ->
            match.groupValues[1] + "\n"
        }
    }
}
