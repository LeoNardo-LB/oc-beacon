package dev.leonardo.ocbeacon.ui.screens.chat.components

import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.looksLikeHtmlPayload
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeHtmlForEmbeddedPreview
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

private enum class HtmlErrorViewMode {
    Page,
    Code,
}

@Composable
internal fun ErrorPayloadContent(
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!looksLikeHtmlPayload(text)) {
        SelectionContainer {
            Text(
                text = text,
                style = textStyle,
                color = textColor,
                modifier = modifier,
            )
        }
        return
    }

    var mode by rememberSaveable(text) { mutableStateOf(HtmlErrorViewMode.Code) }
    val htmlForPreview = remember(text) { normalizeHtmlForEmbeddedPreview(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == HtmlErrorViewMode.Code,
                onClick = { mode = HtmlErrorViewMode.Code },
                label = { Text(stringResource(R.string.chat_error_view_code)) },
            )
            FilterChip(
                selected = mode == HtmlErrorViewMode.Page,
                onClick = { mode = HtmlErrorViewMode.Page },
                label = { Text(stringResource(R.string.chat_error_view_page)) },
            )
        }

        if (mode == HtmlErrorViewMode.Page) {
            val bgColor = MaterialTheme.colorScheme.surface
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.textZoom = 85
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                        setBackgroundColor(bgColor.toArgb())
                    }
                },
                update = { webView ->
                    if (webView.tag != htmlForPreview) {
                        webView.tag = htmlForPreview
                        webView.loadDataWithBaseURL(
                            "https://localhost/",
                            htmlForPreview,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
                onRelease = { wv ->
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM),
                        shape = ShapeTokens.small,
                    )
                    .clip(ShapeTokens.small),
            )
        } else {
            SelectionContainer {
                Text(
                    text = text,
                    style = textStyle,
                    color = textColor,
                )
            }
        }
    }
}
