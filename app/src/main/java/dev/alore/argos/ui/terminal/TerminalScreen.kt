package dev.alore.argos.ui.terminal

import android.content.Context
import android.app.Activity
import android.os.Build
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import dev.alore.argos.R
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.alore.argos.ArgosApplication
import dev.alore.argos.data.TerminalPalette
import dev.alore.argos.data.TerminalSettings
import dev.alore.argos.data.palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit, viewModel: TerminalViewModel = viewModel()) {
    val context = argosApp()
    val settings = context.container.terminalSettings
    val status by viewModel.status.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val uploading by viewModel.isUploading.collectAsStateWithLifecycle()
    val reconnectAttempt by viewModel.autoReconnectAttempt.collectAsStateWithLifecycle()
    val fontSize by settings.fontSize.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val palette = theme.palette(systemDark)
    val fg = Color(palette.foreground)
    val chrome = Color(palette.chrome)
    StatusBarIconsFor(palette, restoreLight = !systemDark)

    val modifiers = remember { StickyModifiers() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.uploadFiles(uris)
    }

    DisposableEffect(terminalView) {
        viewModel.attachedView = terminalView
        onDispose { if (viewModel.attachedView === terminalView) viewModel.attachedView = null }
    }

    Scaffold(
        containerColor = chrome,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = chrome,
                    titleContentColor = fg,
                    navigationIconContentColor = fg,
                    actionIconContentColor = fg,
                ),
                title = {
                    Column {
                        Text(
                            viewModel.sessionName,
                            fontFamily = TerminalFont,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("tmux attach", fontFamily = TerminalFont, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.55f))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Desconectar y volver") }
                },
                actions = {
                    IconButton(onClick = { terminalView?.let(::showKeyboard) }) {
                        Icon(Icons.Outlined.Keyboard, contentDescription = "Mostrar teclado")
                    }
                    IconButton(onClick = viewModel::pasteFromClipboard, enabled = status == TerminalStatus.Connected) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Pegar (texto o imagen)")
                    }
                    IconButton(onClick = { pickFiles.launch("*/*") }, enabled = status == TerminalStatus.Connected && !uploading) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = "Subir archivos al servidor")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(palette.background))) {
                AndroidView(
                    // Breathing room around the text, in the terminal's own background color.
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setTerminalViewClient(ArgosTerminalViewClient(this, modifiers, settings))
                            // setTextSize creates the renderer; anything touching the renderer
                            // (setTypeface) must come after it. Later size changes keep the font.
                            setTextSize(spToPx(ctx, fontSize))
                            terminalTypeface(ctx)?.let(::setTypeface)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            keepScreenOn = true
                            terminalView = this
                        }
                    },
                    update = { view ->
                        val textSize = spToPx(view.context, fontSize)
                        if (view.getTag(R.id.argos_text_size) != textSize) {
                            view.setTextSize(textSize)
                            view.setTag(R.id.argos_text_size, textSize)
                        }
                        val current = session
                        if (current != null && view.currentSession !== current) {
                            view.attachSession(current)
                            view.requestFocus()
                        }
                        applyPalette(view, palette)
                    },
                )

                when (val s = status) {
                    TerminalStatus.Connecting -> ConnectingOverlay(viewModel.sessionName, palette)
                    TerminalStatus.Connected -> Unit
                    TerminalStatus.Ended -> StatusBanner(
                        palette = palette,
                        icon = Icons.Outlined.LinkOff,
                        title = "Sesión desconectada",
                        message = "Te desenganchaste de la sesión (sigue viva en el servidor).",
                        onReconnect = viewModel::reconnect,
                    )
                    is TerminalStatus.Failed -> {
                        val attempt = reconnectAttempt
                        if (attempt != null) {
                            StatusBanner(
                                palette = palette,
                                icon = null,
                                title = "Reconectando… (intento $attempt de ${TerminalViewModel.MAX_AUTO_RECONNECTS})",
                                message = "Se perdió la conexión. Reintentando automáticamente.",
                                onReconnect = null,
                            )
                        } else {
                            StatusBanner(
                                palette = palette,
                                icon = Icons.Outlined.WarningAmber,
                                title = "No se pudo abrir el terminal",
                                message = s.message,
                                onReconnect = viewModel::reconnect,
                            )
                        }
                    }
                }

                if (uploading) UploadPill(palette, Modifier.align(Alignment.TopCenter))
            }
            ExtraKeysBar(
                palette = palette,
                modifiers = modifiers,
                enabled = status == TerminalStatus.Connected,
                onKey = { key -> terminalView?.let { sendExtraKey(it, key, modifiers) } },
            )
        }
    }
}

/** Ctrl/Alt toggles of the extra keys row: one-shot, consumed by the next key. */
class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun reset() {
        ctrl = false
        alt = false
    }
}

private fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: StickyModifiers) {
    val session: TerminalSession = view.currentSession ?: return
    if (view.mEmulator == null) return
    when (key) {
        is ExtraKey.Code -> {
            var keyMod = 0
            if (modifiers.ctrl) keyMod = keyMod or com.termux.terminal.KeyHandler.KEYMOD_CTRL
            if (modifiers.alt) keyMod = keyMod or com.termux.terminal.KeyHandler.KEYMOD_ALT
            view.handleKeyCode(key.keyCode, keyMod)
            modifiers.reset()
        }
        is ExtraKey.Char -> {
            // inputCodePoint applies the sticky Ctrl/Alt (read via the client) and resets them.
            view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, key.char.code, false, false)
        }
        is ExtraKey.Raw -> {
            session.write(key.bytes, 0, key.bytes.size)
            modifiers.reset()
        }
    }
}

/**
 * Applies the theme to the emulator's default colors. Also updated in the global scheme so a
 * terminal reset (RIS) keeps them.
 */
private fun applyPalette(view: TerminalView, palette: TerminalPalette) {
    fun IntArray.fill() {
        palette.ansi.copyInto(this, destinationOffset = 0)
        this[TextStyle.COLOR_INDEX_FOREGROUND] = palette.foreground
        this[TextStyle.COLOR_INDEX_BACKGROUND] = palette.background
        this[TextStyle.COLOR_INDEX_CURSOR] = palette.cursor
    }
    TerminalColors.COLOR_SCHEME.mDefaultColors.fill()
    view.mEmulator?.mColors?.mCurrentColors?.fill()
    view.setBackgroundColor(palette.background)
    view.invalidate()
}

/**
 * JetBrains Mono (bundled, OFL): crisp and consistent on every phone, unlike the system mono.
 * On Android 10+ it falls back to bundled Noto Sans Symbols 2 / Symbols BEFORE the system fonts, so
 * glyphs CLI tools love (Claude Code's ⏺ ⎿ ✻, braille spinners, ✔ ⏸…) render as monochrome text
 * instead of color emoji.
 */
private var cachedTypeface: Typeface? = null

private fun terminalTypeface(context: Context): Typeface? {
    cachedTypeface?.let { return it }
    val typeface = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val res = context.resources
            fun family(fontRes: Int) =
                android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(res, fontRes).build()).build()
            Typeface.CustomFallbackBuilder(family(R.font.jetbrains_mono))
                .addCustomFallback(family(R.font.noto_sans_symbols2))
                .addCustomFallback(family(R.font.noto_sans_symbols))
                .setSystemFallback("monospace")
                .build()
        } else {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono)
        }
    } catch (e: Exception) {
        // Never block the terminal over a font: the system monospace still works.
        Log.e("Argos/Terminal", "Could not load bundled terminal fonts", e)
        null
    }
    cachedTypeface = typeface
    return typeface
}

/** Same font for terminal-ish Compose text (title, keys). */
val TerminalFont = FontFamily(Font(R.font.jetbrains_mono))

/** Status/navigation bar icons readable on the terminal's colors; restored when leaving. */
@Composable
private fun StatusBarIconsFor(palette: TerminalPalette, restoreLight: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(palette.isLight) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = palette.isLight
        controller?.isAppearanceLightNavigationBars = palette.isLight
        onDispose {
            controller?.isAppearanceLightStatusBars = restoreLight
            controller?.isAppearanceLightNavigationBars = restoreLight
        }
    }
}

private fun spToPx(context: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).toInt()

private fun showKeyboard(view: TerminalView) {
    view.requestFocus()
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(view, 0)
}

/** Bridges Termux's view callbacks: keyboard on tap, pinch-to-zoom font, sticky modifiers. */
private class ArgosTerminalViewClient(
    private val view: TerminalView,
    private val modifiers: StickyModifiers,
    private val settings: TerminalSettings,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            settings.setFontSize(settings.fontSize.value + if (scale > 1f) 1 else -1)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard(view)
    override fun shouldBackButtonBeMappedToEscape() = false
    // Char-based input behaves consistently across Gboard/Samsung keyboards.
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = modifiers.ctrl
    override fun readAltKey() = modifiers.alt
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // The view already read the sticky modifiers for this key: consume them.
        modifiers.reset()
        return false
    }

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message.orEmpty(), e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}

@Composable
private fun ConnectingOverlay(sessionName: String, palette: TerminalPalette) {
    val fg = Color(palette.foreground)
    Box(Modifier.fillMaxSize().background(Color(palette.background)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(palette.ansi[4]), strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.size(18.dp))
            Text("Conectando a la sesión", color = fg, style = MaterialTheme.typography.titleMedium)
            Text(sessionName, fontFamily = TerminalFont, color = fg.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StatusBanner(palette: TerminalPalette, icon: ImageVector?, title: String, message: String, onReconnect: (() -> Unit)?) {
    val fg = Color(palette.foreground)
    val accent = Color(palette.ansi[4])
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(palette.chrome),
            contentColor = fg,
            border = BorderStroke(1.dp, fg.copy(alpha = 0.12f)),
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = accent)
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
                }
                if (onReconnect != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onReconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(palette.background)),
                    ) { Text("Reconectar") }
                }
            }
        }
    }
}

@Composable
private fun UploadPill(palette: TerminalPalette, modifier: Modifier = Modifier) {
    val fg = Color(palette.foreground)
    Surface(
        modifier.padding(top = 12.dp),
        shape = CircleShape,
        color = Color(palette.chrome),
        contentColor = fg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.12f)),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color(palette.ansi[4]), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Subiendo al servidor…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun argosApp(): ArgosApplication =
    androidx.compose.ui.platform.LocalContext.current.applicationContext as ArgosApplication
