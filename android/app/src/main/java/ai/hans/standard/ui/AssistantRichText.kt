package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import android.content.ActivityNotFoundException
import android.widget.Toast
import ai.hans.standard.text.AssistantMarkdown
import ai.hans.standard.text.AssistantMarkdownBlock
import ai.hans.standard.text.AssistantMarkdownBlockKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Native selectable, theme-aware assistant text. Parsing runs only when a snapshot changes;
 * this component has no polling, animation, WebView, image loader or network fetch.
 * [onOpenLink] is injectable for tests; the normal path uses Android's public URI handler.
 */
@Composable
fun AssistantRichText(
    raw: String,
    modifier: Modifier = Modifier,
    complete: Boolean = true,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val uiText = rememberHansTextResolver()
    val document = remember(raw, complete) { AssistantMarkdown.parse(raw, complete) }
    val currentOpenLink by rememberUpdatedState(onOpenLink)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val openLink: (String) -> Unit = remember(uriHandler, context) {
        { destination ->
            val safe = AssistantMarkdown.safeLinkUrl(destination)
            if (safe != null) {
                try {
                    val customHandler = currentOpenLink
                    if (customHandler != null) customHandler(safe)
                    else if (AssistantMarkdown.safeFileUrl(safe) != null) {
                        ai.hans.standard.files.AndroidFileLinks(context).openLink(safe)
                    } else uriHandler.openUri(safe)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, uiText.text(R.string.ui_no_app_was_found_to_open_this_link_c1191d), Toast.LENGTH_SHORT).show()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, uiText.text(R.string.ui_this_link_could_not_be_opened_a31d47), Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, uiText.text(R.string.ui_android_did_not_allow_this_link_to_be_opened_3eba7f), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, uiText.text(R.string.ui_file_unavailable_check_file_access_in_settings_48cabd), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            document.blocks.forEachIndexed { index, block ->
                val annotated = remember(block, linkColor, codeBackground, openLink) {
                    assistantAnnotatedText(block, linkColor, codeBackground, openLink)
                }
                val blockTag = Modifier.testTag("assistant_rich_block_$index")
                when (block.kind) {
                    AssistantMarkdownBlockKind.LIST_ITEM -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(block.listMarker.orEmpty(), style = style)
                        Text(annotated, modifier = blockTag.weight(1f, fill = false), style = style)
                    }
                    AssistantMarkdownBlockKind.HEADING -> {
                        val scale = when (block.headingLevel) { 1 -> 1.3f; 2 -> 1.2f; else -> 1.1f }
                        Text(
                            annotated,
                            modifier = blockTag.semantics { heading() },
                            style = style.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = if (style.fontSize.isSp) style.fontSize * scale else style.fontSize,
                                lineHeight = if (style.lineHeight.isSp) style.lineHeight * scale else style.lineHeight,
                            ),
                        )
                    }
                    AssistantMarkdownBlockKind.CODE -> Text(
                        annotated,
                        modifier = blockTag.fillMaxWidth()
                            .background(codeBackground, RoundedCornerShape(8.dp)).padding(12.dp),
                        style = style.copy(fontFamily = FontFamily.Monospace),
                    )
                    AssistantMarkdownBlockKind.QUOTE -> Text(
                        annotated,
                        modifier = blockTag.padding(start = 12.dp),
                        style = style.copy(fontStyle = FontStyle.Italic),
                    )
                    AssistantMarkdownBlockKind.PARAGRAPH -> Text(annotated, modifier = blockTag, style = style)
                }
            }
        }
    }
}

/** One native link range may contain several differently formatted runs of the same label. */
internal fun assistantAnnotatedText(
    block: AssistantMarkdownBlock,
    linkColor: Color,
    codeBackground: Color,
    onOpenLink: (String) -> Unit,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var currentUrl: String? = null
    var currentLinkStart = 0
    fun closeLink() {
        val destination = currentUrl ?: return
        if (builder.length > currentLinkStart) {
            builder.addLink(
                LinkAnnotation.Url(
                    destination,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ),
                    linkInteractionListener = { onOpenLink(destination) },
                ),
                currentLinkStart,
                builder.length,
            )
        }
    }
    block.runs.forEach { run ->
        val safeUrl = run.url?.let(AssistantMarkdown::safeWebUrl)
        if (safeUrl != currentUrl) {
            closeLink()
            currentUrl = safeUrl
            currentLinkStart = builder.length
        }
        val start = builder.length
        builder.append(run.text)
        if (builder.length > start) {
            builder.addStyle(
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) codeBackground else Color.Unspecified,
                ),
                start,
                builder.length,
            )
        }
    }
    closeLink()
    return builder.toAnnotatedString()
}
