package com.ahmedgamil.wakili.core.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/**
 * Markdown for chat replies — same dialect as the web client's dependency-free
 * renderer (public/js/core/markdown.js): headings, bold/italic/strike, inline
 * and fenced code, links, lists, blockquotes, hr. Anything else stays plain.
 */

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock
    data object Rule : MdBlock
}

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.lines()
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += MdBlock.Code(code.toString().trimEnd())
            }

            Regex("^#{1,6}\\s").containsMatchIn(line) -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, line.drop(level).trim())
            }

            Regex("^\\s*([-*_])\\1{2,}\\s*$").matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }

            line.trimStart().startsWith("> ") || line.trim() == ">" -> {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && (lines[i].trimStart().startsWith(">"))) {
                    quote.appendLine(lines[i].trimStart().removePrefix(">").trim())
                    i++
                }
                i--
                blocks += MdBlock.Quote(quote.toString().trim())
            }

            Regex("^\\s*[-*]\\s+").containsMatchIn(line) || Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line) -> {
                flushParagraph()
                val ordered = Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line)
                val items = mutableListOf<String>()
                val itemRe = if (ordered) Regex("^\\s*\\d+\\.\\s+(.*)") else Regex("^\\s*[-*]\\s+(.*)")
                while (i < lines.size) {
                    val m = itemRe.find(lines[i]) ?: break
                    items += m.groupValues[1]
                    i++
                }
                i--
                blocks += MdBlock.Bullets(ordered, items)
            }

            line.isBlank() -> flushParagraph()

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

// code spans | bold | italic | strike | [text](url)
private val INLINE = Regex(
    "(`[^`]+`)|(\\*\\*.+?\\*\\*)|(\\*[^*\\n]+\\*)|(~~.+?~~)|(\\[[^\\]]+\\]\\([^)\\s]+\\))",
)

fun inlineAnnotated(
    text: String,
    codeBg: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val token = m.value
        when {
            // .text code — .9em mono on the panel background
            token.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.9.em),
            ) { append(token.trim('`')) }

            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("**"))
            }

            token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(token.removeSurrounding("~~"))
            }

            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.removeSurrounding("*"))
            }

            token.startsWith("[") -> {
                val label = token.substringAfter('[').substringBefore(']')
                val url = token.substringAfter('(').substringBefore(')')
                withLink(
                    LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                ) { append(label) }
            }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { parseMarkdown(source) }
    val colors = WakiliTheme.colors
    val body = MaterialTheme.typography.bodyLarge

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
    ) {
        blocks.forEach { block ->
            when (block) {
                // .text h1/h2/h3 — 1.5/1.3/1.15em of the 15px base, weight 650
                is MdBlock.Heading -> Text(
                    text = inlineAnnotated(block.text, colors.panel, colors.accent),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontSize = body.fontSize * 1.5f)
                        2 -> MaterialTheme.typography.titleLarge.copy(fontSize = body.fontSize * 1.3f)
                        3 -> MaterialTheme.typography.titleMedium.copy(fontSize = body.fontSize * 1.15f)
                        else -> MaterialTheme.typography.titleMedium.copy(fontSize = body.fontSize)
                    },
                    color = colors.text,
                )

                is MdBlock.Paragraph -> Text(
                    text = inlineAnnotated(block.text, colors.panel, colors.accent),
                    style = body,
                    color = colors.text,
                )

                // .text pre — panel background, border, radius 10, 12.5px mono
                is MdBlock.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.panel, RoundedCornerShape(WakiliDimens.RadiusMd))
                        .border(
                            WakiliDimens.BorderThin,
                            colors.border,
                            RoundedCornerShape(WakiliDimens.RadiusMd),
                        )
                        .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(text = block.text, style = WakiliMono.Small, color = colors.text)
                }

                // .text blockquote — 3px left border, muted
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(WakiliDimens.BorderHeavy)
                            .background(colors.border),
                    )
                    Text(
                        text = inlineAnnotated(block.text, colors.panel, colors.accent),
                        style = body,
                        color = colors.muted,
                        modifier = Modifier.padding(start = WakiliDimens.Space12),
                    )
                }

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space2)) {
                    block.items.forEachIndexed { n, item ->
                        Row {
                            Text(
                                text = if (block.ordered) "${n + 1}." else "•",
                                style = body,
                                color = colors.muted,
                            )
                            Text(
                                text = inlineAnnotated(item, colors.panel, colors.accent),
                                style = body,
                                color = colors.text,
                                modifier = Modifier.padding(start = WakiliDimens.Space8),
                            )
                        }
                    }
                }

                MdBlock.Rule -> HorizontalDivider(
                    color = colors.border,
                    thickness = WakiliDimens.BorderThin,
                )
            }
        }
    }
}
