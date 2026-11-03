package com.mio.irondome.caixapreta

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IdeSupport
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Tone
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/** Iron Dome IA: Verificar Compatibilidade, no menu Help e na engrenagem do painel. */
class CheckCompatibilityAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        CompatibilityDialog.show(e.project ?: return)
    }
}

/**
 * Confere ao abrir o projeto. Se a versão do IntelliJ não comporta algum mecanismo, pergunta se o usuário
 * atualiza a IDE ou trabalha limitado; os demais problemas viram um aviso, até ele pedir para não avisar mais.
 */
class CompatibilityStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val env = Compatibility.environment(project)
        val limited = Compatibility.limited(env)
        if (limited.isNotEmpty() && !IdeSupport.limitedAccepted) {
            ApplicationManager.getApplication().invokeLater({ askUpdateOrLimited(project, env) }, project.disposed)
        }
        val problems = Compatibility.problems(Compatibility.evaluate(env)).filter { it.mechanism !in limited }
        if (problems.isEmpty()) return
        val signature = problems.joinToString("|") { "${it.mechanism.name}:${it.detail}" }
        if (PropertiesComponent.getInstance().getValue(DISMISSED_KEY) == signature) return
        val title = if (problems.size == 1) "Iron Dome IA: ${problems.single().mechanism.title} com problema"
        else "Iron Dome IA: ${problems.size} mecanismos com problema"
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, problems.joinToString("<br>") { "<b>${it.mechanism.title}:</b> ${escapeHtml(it.detail)}" }, NotificationType.WARNING)
            .setIcon(IronDomeIcons.caixaPreta)
            .addAction(NotificationAction.createSimpleExpiring("Ver Compatibilidade") { CompatibilityDialog.show(project) })
            .addAction(NotificationAction.createSimpleExpiring("Ver Logs de Erro") { ErrorLogDialog.show(project) })
            .addAction(NotificationAction.createSimpleExpiring("Não Avisar de Novo") {
                PropertiesComponent.getInstance().setValue(DISMISSED_KEY, signature)
            })
            .notify(project)
    }

    /** Atualizar não grava a escolha: se a IDE continuar a mesma, pergunta de novo na próxima vez. */
    private fun askUpdateOrLimited(project: Project, env: Environment) {
        val choice = Messages.showDialog(
            project,
            Compatibility.limitedMessage(env),
            "Iron Dome IA: atualize o IntelliJ",
            arrayOf(UPDATE, LIMITED),
            0,
            Messages.getWarningIcon(),
        )
        when (choice) {
            0 -> IdeSupport.openUpdate()
            1 -> {
                IdeSupport.limitedAccepted = true
                val titles = Compatibility.limited(env).joinToString { it.title }
                NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
                    .createNotification(
                        "Iron Dome IA no modo limitado",
                        "Desligados nesta versão do IntelliJ: ${escapeHtml(titles)}. O restante funciona normalmente.",
                        NotificationType.INFORMATION,
                    )
                    .setIcon(IronDomeIcons.caixaPreta)
                    .addAction(NotificationAction.createSimpleExpiring(UPDATE) { IdeSupport.openUpdate() })
                    .addAction(NotificationAction.createSimpleExpiring("Ver Compatibilidade") { CompatibilityDialog.show(project) })
                    .notify(project)
            }
        }
    }

    private companion object {
        /** Guarda os problemas dispensados: um problema diferente volta a avisar. */
        const val DISMISSED_KEY = "IronDome.Compatibility.Dismissed"
        const val UPDATE = "Atualizar o IntelliJ"
        const val LIMITED = "Trabalhar Limitado"
    }
}

internal class CompatibilityDialog private constructor(
    private val project: Project,
    private val env: Environment,
    private val checks: List<CompatibilityCheck>,
    private val errors: List<PluginError>,
) : DialogWrapper(project, true) {

    init {
        title = "Compatibilidade do Iron Dome IA"
        setOKButtonText("Fechar")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createLeftSideActions(): Array<Action> = listOfNotNull(
        object : AbstractAction("Atualizar o IntelliJ") {
            override fun actionPerformed(e: ActionEvent) {
                close(OK_EXIT_CODE)
                IdeSupport.openUpdate()
            }
        }.takeIf { env.missingApis.isNotEmpty() || env.ideVersionFailures.isNotEmpty() || env.olderThanTested },
        object : AbstractAction("Copiar Diagnóstico") {
            override fun actionPerformed(e: ActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(Compatibility.markdown(env, checks, errors)))
            }
        },
        object : AbstractAction("Ver Logs de Erro") {
            override fun actionPerformed(e: ActionEvent) {
                close(OK_EXIT_CODE)
                ErrorLogDialog.show(project)
            }
        },
        object : AbstractAction("Reportar Bug") {
            override fun actionPerformed(e: ActionEvent) {
                close(OK_EXIT_CODE)
                val action = ActionManager.getInstance().getAction("IronDome.ReportBug") ?: return
                ActionManager.getInstance().tryToExecute(action, null, null, ActionPlaces.UNKNOWN, true)
            }
        },
    ).toTypedArray()

    override fun createCenterPanel(): JComponent {
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        checks.forEach { list.add(row(it)) }
        return FormBuilder.createFormBuilder()
            .addComponent(comment("Plugin ${env.pluginVersion} · ${env.ide} · ${env.system}"))
            .addComponent(
                comment(
                    "Testado com o IntelliJ ${IdeSupport.TESTED_VERSION} (build ${IdeSupport.TESTED_BUILD}) ou mais novo" +
                        if (env.olderThanTested) ": esta IDE é mais antiga, atualize se algo não funcionar." else ".",
                ),
            )
            .addComponent(TitledSeparator("Mecanismos"))
            .addComponent(list)
            .addComponent(TitledSeparator("Erros recentes do plugin no log da IDE"))
            .addComponent(errorsPanel())
            .panel
            .apply { preferredSize = JBUI.size(700, preferredSize.height) }
    }

    private fun row(check: CompatibilityCheck): JComponent {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel(icon(check.status)))
            add(JBLabel(check.mechanism.title).apply { font = JBFont.label().asBold() })
            add(Pill(check.status.label, tone(check.status)))
        }
        val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyLeft(28)
            add(JBLabel("<html>${escapeHtml(check.detail)}</html>"))
            check.fix?.let { add(comment(it)) }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(header, BorderLayout.NORTH)
            add(text, BorderLayout.CENTER)
        }
    }

    private fun errorsPanel(): JComponent {
        if (errors.isEmpty()) return comment("Nenhum erro ou aviso do plugin no idea.log.")
        val text = errors.joinToString("\n\n") { error ->
            buildString {
                append("${error.time} · ${error.level}: ${error.message}")
                error.origin?.let { append("\nOrigem no plugin: $it") }
                BugReportMarkdown.compactStack(error.stack).forEach { append('\n').append(it) }
            }
        }
        val area = JBTextArea(text).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
            caretPosition = 0
        }
        return JBScrollPane(area).apply { preferredSize = JBUI.size(680, 220) }
    }

    private fun icon(status: CheckStatus): Icon = when (status) {
        CheckStatus.OK -> AllIcons.General.InspectionsOK
        CheckStatus.INFO -> AllIcons.General.Information
        CheckStatus.WARNING -> AllIcons.General.Warning
        CheckStatus.ERROR -> AllIcons.General.Error
    }

    private fun tone(status: CheckStatus): Tone = when (status) {
        CheckStatus.OK -> Tone.GREEN
        CheckStatus.INFO -> Tone.BLUE
        CheckStatus.WARNING -> Tone.YELLOW
        CheckStatus.ERROR -> Tone.RED
    }

    private fun comment(text: String) = JBLabel("<html>${escapeHtml(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    companion object {
        private const val LOG_ERRORS = 10

        /** Lê o ambiente e o log fora da EDT e abre o diálogo. */
        fun show(project: Project) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val env = Compatibility.environment(project)
                val checks = Compatibility.evaluate(env)
                val errors = runCatching { PluginLog.recentErrors(LOG_ERRORS) }.getOrDefault(emptyList())
                ApplicationManager.getApplication().invokeLater({
                    CompatibilityDialog(project, env, checks, errors).show()
                }, project.disposed)
            }
        }
    }
}

private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
