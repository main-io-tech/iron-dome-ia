package com.mio.irondome.caixapreta

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefApp
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.TabFailures
import com.mio.irondome.base.rootPath
import com.mio.irondome.interceptor.AgentRunner
import java.awt.KeyboardFocusManager
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** O ambiente do bug, lido na EDT na hora de abrir o formulário. */
internal class BugContext(val activeTab: Mechanism?, val entries: List<Pair<String, String>>) {

    companion object {
        private const val PLUGIN_ID = "com.mio.irondome"

        fun collect(project: Project): BugContext {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewToolWindowFactory.ID)
            // Com o painel dividido, cada lado tem uma aba selecionada: vale a do lado com o foco.
            val tab = toolWindow?.takeIf { it.isVisible }?.let { window ->
                val selected = ReviewToolWindowFactory.contents(window).filter { it.isSelected }
                val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                (selected.firstOrNull { focus != null && SwingUtilities.isDescendingFrom(focus, it.component) } ?: selected.firstOrNull())
                    ?.displayName
            }
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
            val openFile = if (editor != null && file != null) {
                val path = project.rootPath()?.let { file.path.removePrefix("$it/") } ?: file.path
                "$path, linha ${editor.caretModel.logicalPosition.line + 1}"
            } else {
                "nenhum"
            }
            val app = ApplicationInfo.getInstance()
            return BugContext(
                Mechanism.ofTab(tab),
                listOf(
                    "Versão do plugin" to (PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "desconhecida"),
                    "IDE" to "${app.fullApplicationName} (build ${app.build.asString()})",
                    "Sistema operacional" to "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} (${SystemInfo.OS_ARCH})",
                    "Java" to "${SystemInfo.JAVA_RUNTIME_VERSION} (${SystemInfo.JAVA_VENDOR})",
                    "Plugins opcionais" to listOf("com.intellij.java" to "Java", "JUnit" to "JUnit")
                        .joinToString { (id, name) -> "$name ${if (Compatibility.pluginEnabled(id)) "ativo" else "inativo"}" },
                    "Navegador embutido (JCEF)" to if (runCatching { JBCefApp.isSupported() }.getOrDefault(false)) "disponível" else "indisponível",
                    "Abas que não abriram" to TabFailures.all.entries.joinToString("; ") { (title, error) -> "$title ($error)" }.ifEmpty { "nenhuma" },
                    "Controle de versão" to ProjectLevelVcsManager.getInstance(project).allActiveVcss
                        .joinToString { it.displayName }.ifEmpty { "nenhum" },
                    "Aba ativa do Iron Dome IA" to (tab ?: "painel fechado"),
                    "Arquivo aberto" to openFile,
                ),
            )
        }
    }
}

/**
 * Gera cada relatório numa pasta própria dentro de [folder] e, se pedido, cria a issue no Linear com as
 * capturas. A pasta e o destino escolhidos ficam como padrão para o próximo relatório.
 */
object BugReports {

    private const val FOLDER_KEY = "IronDome.Bugs.Folder"
    private const val LOCAL_ONLY_KEY = "IronDome.Bugs.LocalOnly"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    val defaultFolder: Path get() = Path.of(System.getProperty("user.home"), "iron-dome-ia", "bugs")

    var folder: Path
        get() = PropertiesComponent.getInstance().getValue(FOLDER_KEY)
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: defaultFolder
        set(value) = PropertiesComponent.getInstance().setValue(FOLDER_KEY, value.toString().takeIf { value != defaultFolder })

    /** Último destino escolhido: só gerar o `.md` local, ou também enviar ao Linear. */
    var localOnly: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(LOCAL_ONLY_KEY)
        set(value) = PropertiesComponent.getInstance().setValue(LOCAL_ONLY_KEY, value)

    /** Pasta `iron-dome/bugs` do projeto aberto, ao lado das revisões do Radar. */
    fun projectFolder(project: Project): Path? = project.rootPath()?.let { Path.of(it, "iron-dome", "bugs") }

    /** [recording] é um arquivo temporário, movido para a pasta do relatório. */
    fun submit(project: Project, report: BugReport, screenshot: BufferedImage?, recording: Path?, folder: Path, sendToLinear: Boolean) {
        object : Task.Backgroundable(project, if (sendToLinear) "Reportando o bug" else "Gerando o relatório de bug", false) {
            private var dir: Path? = null
            private var issue: LinearClient.Issue? = null
            private var linearError: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Salvando o relatório"
                val dir = folder.resolve("bug-${report.createdAt.format(STAMP)}").also { Files.createDirectories(it) }
                this.dir = dir
                screenshot?.let { ImageIO.write(it, "png", dir.resolve(BugReportMarkdown.SCREENSHOT).toFile()) }
                recording?.let { Files.move(it, dir.resolve(BugReportMarkdown.RECORDING), StandardCopyOption.REPLACE_EXISTING) }
                Files.writeString(dir.resolve(BugReportMarkdown.FILE_NAME), BugReportMarkdown.render(report))
                if (!sendToLinear) return
                indicator.text = "Enviando ao Linear"
                try {
                    issue = sendToLinear(report, dir)
                } catch (error: ProcessCanceledException) {
                    throw error
                } catch (error: Exception) {
                    linearError = error.message ?: error.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                val dir = dir ?: return
                notifyResult(project, report, dir, issue, linearError, sentToLinear = sendToLinear)
            }

            override fun onThrowable(error: Throwable) {
                group().createNotification(
                    "Não foi possível salvar o relatório de bug",
                    error.message ?: error.javaClass.simpleName,
                    NotificationType.ERROR,
                ).notify(project)
            }
        }.queue()
    }

    private fun retry(project: Project, report: BugReport, dir: Path) {
        object : Task.Backgroundable(project, "Enviando o bug ao Linear", false) {
            private var issue: LinearClient.Issue? = null
            private var linearError: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    issue = sendToLinear(report, dir)
                } catch (error: ProcessCanceledException) {
                    throw error
                } catch (error: Exception) {
                    linearError = error.message ?: error.javaClass.simpleName
                }
            }

            override fun onSuccess() = notifyResult(project, report, dir, issue, linearError, sentToLinear = true)
        }.queue()
    }

    private fun sendToLinear(report: BugReport, dir: Path): LinearClient.Issue {
        val apiKey = LinearSettings.apiKey
            ?: throw LinearException("Linear não configurado: informe a API key em Settings > Tools > Iron Dome IA.")
        val slugId = LinearSettings.projectSlugId(LinearSettings.project)
            ?: throw LinearException("URL do projeto do Linear não reconhecida em Settings > Tools > Iron Dome IA.")
        val client = LinearClient(apiKey)
        val project = client.project(slugId)
        val team = project.teamFor(LinearSettings.teamKey)
        val urls = buildMap {
            if (report.screenshot) put(BugReportMarkdown.SCREENSHOT, client.upload(dir.resolve(BugReportMarkdown.SCREENSHOT), "image/png"))
            if (report.recording) put(BugReportMarkdown.RECORDING, client.upload(dir.resolve(BugReportMarkdown.RECORDING), "image/gif"))
        }
        return client.createIssue(
            team.id,
            project.id,
            "[Bug] ${report.mechanism.title}: ${report.title.trim()}",
            BugReportMarkdown.render(report) { urls[it] ?: it },
        )
    }

    private fun notifyResult(project: Project, report: BugReport, dir: Path, issue: LinearClient.Issue?, linearError: String?, sentToLinear: Boolean) {
        val notification = when {
            issue != null -> group().createNotification("Bug ${issue.identifier} criado no Linear", report.title, NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring("Abrir no Linear") { BrowserUtil.browse(issue.url) })
            sentToLinear -> group().createNotification("Relatório de bug salvo, mas não foi para o Linear", linearError.orEmpty(), NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Tentar de Novo") { retry(project, report, dir) })
                .addAction(NotificationAction.createSimple("Configurar Linear") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, LinearConfigurable::class.java)
                })
            else -> {
                openReport(project, dir)
                group().createNotification("Relatório de bug gerado", dir.toString(), NotificationType.INFORMATION)
            }
        }
        notification.addOpenActions(project, dir).notify(project)
    }

    private fun Notification.addOpenActions(project: Project, dir: Path) = apply {
        addAction(NotificationAction.createSimple("Abrir ${BugReportMarkdown.FILE_NAME}") { openReport(project, dir) })
        addAction(NotificationAction.createSimple("Abrir Pasta") { RevealFileAction.openDirectory(dir.toFile()) })
        if (AgentRunner.getInstance(project).available()) {
            addAction(NotificationAction.createSimpleExpiring("Analisar com IA") {
                BugAnalysis.analyzeReport(project, dir.resolve(BugReportMarkdown.FILE_NAME))
            })
        }
    }

    private fun openReport(project: Project, dir: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(BugReportMarkdown.FILE_NAME))
            ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
}
