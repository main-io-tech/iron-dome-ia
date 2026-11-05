package com.mio.irondome.base.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel

/**
 * Uma aba desenhada no navegador embutido (JCEF), com o visual do Tomahawk IA. A página manda mensagens JSON
 * (`{c: comando, ...}`) que chegam em [onMessage] na EDT. Redesenhar preserva a rolagem e espera o usuário sair do
 * campo em que está digitando. Sem JCEF (como nos testes), a página ainda pode ser montada por [html].
 */
abstract class WebTab(protected val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (supported) JBCefBrowser() else null
    private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    /** O tom da página: `teal`, `purple`, `blue`… */
    protected abstract val accent: String

    /** O conteúdo da página, dentro do `<div class="wrap">`. */
    protected abstract fun body(): String

    protected abstract fun onMessage(command: String, data: JsonObject)

    @Volatile
    private var scrollY = 0
    private var typing = false
    private var pending = false
    private var lastHtml: String? = null

    init {
        browser?.let {
            Disposer.register(this, it)
            add(it.component, BorderLayout.CENTER)
        }
        query?.let { q ->
            Disposer.register(this, q)
            q.addHandler { raw ->
                ApplicationManager.getApplication().invokeLater({ dispatch(raw) }, project.disposed)
                null
            }
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener { refresh(force = true) })
    }

    private fun dispatch(raw: String) {
        val data = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        when (val command = data.str("c").orEmpty()) {
            "scroll" -> scrollY = data.get("y")?.asInt ?: scrollY
            "focus" -> typing = true
            "blur" -> {
                typing = false
                if (pending) refresh()
            }
            else -> runCatching { onMessage(command, data) }.onFailure { LOG.warn("A aba web do Iron Dome não tratou \"$command\"", it) }
        }
    }

    /** Redesenha a página; enquanto o usuário digita num campo, espera ele sair dele, para não perder o foco. */
    fun refresh(force: Boolean = false) {
        val browser = browser ?: return
        if (browser.isDisposed) return
        if (typing && !force) {
            pending = true
            return
        }
        pending = false
        val html = html()
        if (html == lastHtml) return
        lastHtml = html
        browser.loadHTML(html)
    }

    /** A página inteira, como o navegador a recebe. */
    fun html(): String {
        val bridge = query?.let { "function(msg){ ${it.inject("msg")} }" } ?: "function(msg){}"
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><style>")
            append(WebUi.css(theme(), accent))
            append("</style></head><body><div class=\"wrap\">").append(body()).append("</div>")
            append("<script>").append(WebUi.script(bridge, scrollY)).append("</script></body></html>")
        }
    }

    private fun theme(): HtmlTheme {
        val background = UIUtil.getPanelBackground()
        val foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        return HtmlTheme(!JBColor.isBright(), hex(background), hex(foreground))
    }

    private fun hex(color: Color) = "#%02X%02X%02X".format(color.red, color.green, color.blue)

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<WebTab>()

        /** Na 2026.2 ou mais nova o JCEF é um plugin à parte: sem ele, nem as classes existem. */
        val supported: Boolean get() = runCatching { JBCefApp.isSupported() }.getOrDefault(false)

        fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

        fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

        fun esc(text: String) = WebUi.esc(text)
    }
}
