package com.mio.irondome.impacto

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JComponent

/**
 * Diagrama desenhado com Cytoscape.js no navegador embutido. Mensagens da página chegam em
 * [onMessage] na EDT, no formato `acao:nomeQualificado`.
 */
class DiagramBrowser(parent: Disposable, private val onMessage: (String) -> Unit) {

    private val browser: JBCefBrowser?
    private val query: JBCefJSQuery?
    val component: JComponent

    init {
        if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()
            Disposer.register(parent, browser)
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            Disposer.register(parent, query)
            query.addHandler { message ->
                if (message.startsWith(PREF_PREFIX)) savePref(message.removePrefix(PREF_PREFIX))
                else ApplicationManager.getApplication().invokeLater { onMessage(message) }
                null
            }
            this.browser = browser
            this.query = query
            component = browser.component
        } else {
            browser = null
            query = null
            component = JBPanelWithEmptyText().withEmptyText("O navegador embutido (JCEF) não está disponível nesta IDE")
        }
    }

    /** [changes]: tipo de alteração não commitada por classe; [vcs] diz se o projeto usa controle de versão. */
    fun show(graph: DependencyGraph, changes: Map<String, FileChange>, vcs: Boolean) {
        val browser = browser ?: return
        val html = template
            .replace("/*IRON_DOME_THEME_CSS*/", theme().entries.joinToString(" ") { (key, value) -> "--$key: $value;" })
            .replace("/*IRON_DOME_THEME_JSON*/", Json.obj(theme()))
            .replace("/*IRON_DOME_BRIDGE*/", query!!.inject("message"))
            .replace("/*IRON_DOME_DATA*/", Json.safeForScript(toJson(graph, changes, vcs)))
            .replace("/*IRON_DOME_CYTOSCAPE*/", cytoscape)
        browser.loadHTML(html)
    }

    /** Atualiza só as marcas de alteração, sem refazer o layout. */
    fun updateChanges(changes: Map<String, FileChange>, vcs: Boolean) {
        val cef = browser?.cefBrowser ?: return
        val json = Json.safeForScript(changesJson(changes))
        cef.executeJavaScript("window.ironDome && window.ironDome.setChanges($json, $vcs)", cef.url, 0)
    }

    private fun changesJson(changes: Map<String, FileChange>): String =
        Json.obj(changes.mapValues { (_, change) -> Json.raw(Json.obj("change" to change.name, "label" to change.label)) })

    fun showMessage(text: String) {
        val theme = theme()
        browser?.loadHTML(
            """
            <html><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
            background:${theme["bg"]};color:${theme["muted"]};font:13px -apple-system,'Segoe UI',sans-serif;
            text-align:center;padding:24px;box-sizing:border-box">${Json.escapeHtml(text)}</body></html>
            """.trimIndent(),
        )
    }

    fun select(qualifiedName: String) {
        val cef = browser?.cefBrowser ?: return
        cef.executeJavaScript("window.ironDome && window.ironDome.select(${Json.string(qualifiedName)})", cef.url, 0)
    }

    private fun toJson(graph: DependencyGraph, changes: Map<String, FileChange>, vcs: Boolean): String = Json.obj(
        "focus" to graph.focus,
        "truncated" to graph.truncated,
        "cycles" to graph.cycleCount,
        "vcs" to vcs,
        "changes" to Json.raw(changesJson(changes)),
        "prefs" to Json.raw(Json.obj(PANELS.associateWith { PropertiesComponent.getInstance().getBoolean(prefKey(it)) })),
        "nodes" to graph.nodes.map { node ->
            Json.raw(
                Json.obj(
                    "id" to node.qualifiedName,
                    "name" to node.name,
                    "pkg" to node.packageName,
                    "kind" to node.kind.label,
                    "afferent" to node.afferent,
                    "efferent" to node.efferent,
                    "level" to node.level,
                    "cycle" to node.inCycle,
                    "risk" to node.risk.name,
                    "riskLabel" to node.risk.label,
                    "riskReason" to MetricGuide.riskReason(node),
                    "coverage" to node.tests.coverage.name,
                    "coverageLabel" to node.tests.coverage.label,
                    "tests" to node.tests.testClasses,
                    "attention" to node.needsAttention,
                    "advice" to MetricGuide.advice(node),
                    "changeAdvice" to MetricGuide.changeAdvice(node),
                ),
            )
        },
        "edges" to graph.edges.map { edge ->
            Json.raw(Json.obj("source" to edge.from, "target" to edge.to, "kind" to edge.kind.name, "cycle" to edge.inCycle))
        },
        "help" to MetricGuide.topics.map { topic ->
            Json.raw(Json.obj("id" to topic.id, "title" to topic.title, "text" to topic.text))
        },
    )

    private fun theme(): Map<String, String> {
        val dark = !JBColor.isBright()
        return linkedMapOf(
            "bg" to hex(UIUtil.getPanelBackground()),
            "panel" to hex(JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())),
            "fg" to hex(UIUtil.getLabelForeground()),
            "muted" to hex(NamedColorUtil.getInactiveTextColor()),
            "border" to hex(JBColor.border()),
            "accent" to hex(JBUI.CurrentTheme.Focus.focusColor()),
            "edge" to if (dark) "#8b949e" else "#6e7781",
            "cycle" to if (dark) "#f85149" else "#cf222e",
            "cycleBg" to if (dark) "#4a1a1e" else "#ffebe9",
            "match" to if (dark) "#d2a8ff" else "#8250df",
            "low" to if (dark) "#2e6b3f" else "#a7e3b5",
            "medium" to if (dark) "#7a6420" else "#f5db8b",
            "high" to if (dark) "#7d2e2e" else "#f4a7a3",
            "nodeText" to if (dark) "#e6edf3" else "#1f2328",
            "changed" to hex(FileStatus.MODIFIED.color ?: JBColor.BLUE),
        )
    }

    private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    /** `painel:1` minimiza o painel da página, `painel:0` mostra. */
    private fun savePref(pref: String) {
        val panel = pref.substringBefore(':').takeIf { it in PANELS } ?: return
        PropertiesComponent.getInstance().setValue(prefKey(panel), pref.substringAfter(':') == "1")
    }

    private companion object {
        const val PREF_PREFIX = "pref:"
        /** Painéis da página que o usuário pode minimizar: a legenda e os detalhes da classe. */
        val PANELS = listOf("legend", "info")

        fun prefKey(panel: String) = "irondome.diagram.collapsed.$panel"

        val template: String by lazy { resource("/diagram/diagram.html") }
        val cytoscape: String by lazy { resource("/diagram/cytoscape.min.js") }

        fun resource(path: String): String =
            DiagramBrowser::class.java.getResourceAsStream(path)!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** JSON mínimo para mandar o grafo à página, sem depender de biblioteca. */
internal object Json {

    class Raw(val json: String)

    fun raw(json: String) = Raw(json)

    fun obj(vararg entries: Pair<String, Any?>): String = obj(entries.toMap())

    fun obj(entries: Map<String, Any?>): String =
        entries.entries.joinToString(",", "{", "}") { (key, value) -> "${string(key)}:${value(value)}" }

    fun string(text: String): String = buildString {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' || char == '\u2028' || char == '\u2029' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    /** Impede que um `</script>` dentro dos dados feche o bloco de script da página. */
    fun safeForScript(json: String): String = json.replace("</", "<\\/")

    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun value(value: Any?): String = when (value) {
        null -> "null"
        is Raw -> value.json
        is String -> string(value)
        is Boolean, is Int, is Long -> value.toString()
        is Double -> if (value.isFinite()) "%.4f".format(java.util.Locale.ROOT, value) else "null"
        is Collection<*> -> value.joinToString(",", "[", "]") { value(it) }
        else -> string(value.toString())
    }
}
