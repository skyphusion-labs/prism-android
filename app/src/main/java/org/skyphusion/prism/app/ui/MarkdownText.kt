package org.skyphusion.prism.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Lightweight markdown for chat bubbles (iOS AttributedString markdown parity).
 * Supports **bold**, *italic*, `code`, and [label](url) links (underline only).
 */
@Composable
fun MarkdownText(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val annotated =
    remember(text, color) {
      parseSimpleMarkdown(text, color)
    }
  Text(text = annotated, color = color, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

internal fun parseSimpleMarkdown(raw: String, baseColor: Color): AnnotatedString {
  // Escape-free sequential parse for common inline markers.
  return buildAnnotatedString {
    var i = 0
    val s = raw
    while (i < s.length) {
      when {
        s.startsWith("**", i) -> {
          val end = s.indexOf("**", i + 2)
          if (end > i) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
              append(s.substring(i + 2, end))
            }
            i = end + 2
          } else {
            append(s[i])
            i++
          }
        }
        s.startsWith("`", i) -> {
          val end = s.indexOf('`', i + 1)
          if (end > i) {
            withStyle(
              SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = baseColor,
                background = baseColor.copy(alpha = 0.12f),
              ),
            ) {
              append(s.substring(i + 1, end))
            }
            i = end + 1
          } else {
            append(s[i])
            i++
          }
        }
        s.startsWith("*", i) && !s.startsWith("**", i) -> {
          val end = s.indexOf('*', i + 1)
          if (end > i) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
              append(s.substring(i + 1, end))
            }
            i = end + 1
          } else {
            append(s[i])
            i++
          }
        }
        s.startsWith("[", i) -> {
          val mid = s.indexOf("](", i)
          val end = if (mid > i) s.indexOf(')', mid + 2) else -1
          if (mid > i && end > mid) {
            val label = s.substring(i + 1, mid)
            withStyle(
              SpanStyle(
                color = baseColor,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium,
              ),
            ) {
              append(label)
            }
            i = end + 1
          } else {
            append(s[i])
            i++
          }
        }
        else -> {
          append(s[i])
          i++
        }
      }
    }
  }
}
