package com.mio.irondome.caixapreta

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.Consumer
import java.awt.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Botão Reportar ao Iron Dome IA nos erros que a IDE atribui ao plugin (IDE Internal Errors): abre o
 * Reportar Bug com o erro escolhido no lugar do erro mais recente do log.
 */
class IronDomeErrorSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Reportar ao Iron Dome IA"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val event = events.firstOrNull() ?: return false
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent))
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return false
        ReportBugAction.open(project, errorOf(event, additionalInfo))
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** [additionalInfo] é o que o usuário escreveu na janela de erros da IDE. */
        fun errorOf(event: IdeaLoggingEvent, additionalInfo: String?, now: LocalDateTime = LocalDateTime.now()): PluginError {
            val stack = event.throwableText.orEmpty().lines().filter { it.isNotBlank() }.map { it.trimEnd() }
            val message = listOfNotNull(event.message?.trim()?.ifEmpty { null }, additionalInfo?.trim()?.ifEmpty { null }?.let { "Nota: $it" })
                .joinToString(" · ")
                .ifEmpty { stack.firstOrNull().orEmpty() }
            return PluginError(now.format(TIME), "ERROR", message, stack)
        }
    }
}
