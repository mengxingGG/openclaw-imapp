package ai.openclaw.imapp.ui.chat

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

private fun looksLikeMarkdown(text: String): Boolean {
    if (text.isBlank()) return false
    if (text.contains("```")) return true
    if (Regex("^(#{1,3})\\s+").containsMatchIn(text)) return true
    if (Regex("^\\|.*\\|$", RegexOption.MULTILINE).containsMatchIn(text) &&
        Regex("^\\|?[\\s-:|]+\\|?$", RegexOption.MULTILINE).containsMatchIn(text)) return true
    if (!text.contains('\n')) return false
    var weak = 0
    if (Regex("\\*\\*[^*]+\\*\\*").containsMatchIn(text)) weak++
    if (Regex("^[-*+]\\s+", RegexOption.MULTILINE).containsMatchIn(text)) weak++
    if (Regex("^\\d+\\.\\s+", RegexOption.MULTILINE).containsMatchIn(text)) weak++
    if (Regex("\\[.+\\]\\(.+\\)").containsMatchIn(text)) weak++
    return weak >= 2
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
) {
    val isComplex = remember(markdown) { looksLikeMarkdown(markdown) }

    if (!isComplex) {
        val clean = remember(markdown) {
            markdown
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("\\*([^*]+)\\*"), "$1")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        }
        androidx.compose.material3.Text(text = clean, modifier = modifier, style = fontSize)
        return
    }

    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val markwon = remember { Markwon.builder(context).usePlugin(StrikethroughPlugin.create()).usePlugin(TablePlugin.create(context)).build() }

    val textColor = if (isDark) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(2f, 1.3f)
                setIncludeFontPadding(false)
                typeface = Typeface.DEFAULT
            }
        },
        update = { textView ->
            markwon.setMarkdown(textView, markdown)
        },
        modifier = modifier,
    )
}
