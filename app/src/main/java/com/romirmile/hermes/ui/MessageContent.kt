package com.romirmile.hermes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romirmile.hermes.R
import com.romirmile.hermes.ToolActivity

/**
 * Very small Markdown subset renderer: fenced code blocks, inline `code` and
 * **bold**. Anything else is shown verbatim — the assistant often answers with
 * shell commands and paths, so predictable output beats clever parsing.
 */
sealed interface Block {
    data class Code(val code: String, val language: String) : Block
    data class Plain(val text: String) : Block
}

fun parseBlocks(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val text = StringBuilder()
    val code = StringBuilder()
    var language = ""
    var inCode = false

    fun flushText() {
        if (text.isNotEmpty()) {
            blocks += Block.Plain(text.toString())
            text.setLength(0)
        }
    }

    fun flushCode() {
        blocks += Block.Code(code.toString(), language)
        code.setLength(0)
        language = ""
    }

    source.split("\n").forEach { line ->
        val fence = line.trimStart().startsWith("```")
        if (fence) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushText()
                language = line.trim().removePrefix("```").trim()
                inCode = true
            }
        } else if (inCode) {
            code.append(line).append('\n')
        } else {
            text.append(line).append('\n')
        }
    }
    if (inCode) flushCode() else flushText()
    if (blocks.isEmpty()) blocks += Block.Plain("")
    return blocks
}

fun inlineStyles(source: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < source.length) {
        val bold = source.indexOf("**", i)
        val code = source.indexOf('`', i)
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull() ?: -1
        if (next < 0) {
            append(source.substring(i))
            break
        }
        if (next > i) append(source.substring(i, next))
        if (next == bold) {
            val end = source.indexOf("**", bold + 2)
            if (end < 0) {
                append(source.substring(bold))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(source.substring(bold + 2, end))
            }
            i = end + 2
        } else {
            val end = source.indexOf('`', code + 1)
            if (end < 0) {
                append(source.substring(code))
                break
            }
            withStyle(SpanStyle(fontFamily = MonoFamily)) {
                append(source.substring(code + 1, end))
            }
            i = end + 1
        }
    }
}

@Composable
fun MessageBody(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { parseBlocks(text) }
    SelectionContainer {
        Column(modifier) {
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is Block.Code -> CodeBlock(block)
                    is Block.Plain -> {
                        val body = if (streaming && index == blocks.lastIndex) {
                            block.text + "▍"
                        } else {
                            block.text
                        }
                        Text(
                            text = inlineStyles(body),
                            color = color,
                            fontSize = 15.sp,
                            lineHeight = 23.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: Block.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    block.code.trimEnd('\n'),
                    fontFamily = MonoFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ToolActivityRow(activity: List<ToolActivity>) {
    if (activity.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        activity.forEach { item -> ToolActivityCard(item) }
    }
}

/**
 * One agent-activity line. The summary stays one line; tapping it reveals what the agent actually
 * passed in, which is what makes a long tool run debuggable from the phone.
 */
@Composable
private fun ToolActivityCard(item: ToolActivity) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val detail = listOfNotNull(
        item.preview.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull()?.take(90),
        if (item.running) stringResource(R.string.status_running)
        else item.duration?.let { "%.1fs".format(it) }
    ).joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.preview.isNotBlank()) { expanded = !expanded }
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Icon(
                imageVector = when {
                    item.error -> Icons.Default.ErrorOutline
                    item.running -> Icons.Default.Build
                    else -> Icons.Default.Check
                },
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.tool.ifBlank { stringResource(R.string.agent_activity) },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2
                    )
                }
                if (expanded && item.preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.preview,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

