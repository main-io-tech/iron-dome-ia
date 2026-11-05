package com.mio.irondome.patrulha

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * O modo revisão no editor do plugin de GitHub da IDE, que liga sozinho com o checkout de um PR, mostra os
 * comentários de todos com Editar e Excluir, quando o GitHub deixa (como para quem administra o repositório).
 * Durante a revisão da Patrulha ele fica desligado, para os comentários de outras pessoas serem só os cartões de
 * leitura dela; ao encerrar, volta como estava.
 *
 * A configuração é interna ao plugin de GitHub: o acesso é por reflexão, e versões da IDE sem ela seguem sem mudança.
 */
internal object IdeEditorReview {

    private val LOG = logger<IdeEditorReview>()
    private const val SETTINGS = "org.jetbrains.plugins.github.pullrequest.config.GithubPullRequestsProjectUISettings"

    fun suspend(project: Project) {
        val patrulha = PatrulhaService.getInstance(project)
        if (patrulha.ideEditorReviewBefore != null) return
        val settings = settings(project) ?: return
        runCatching {
            val before = settings.javaClass.getMethod("getEditorReviewEnabled").invoke(settings) as Boolean
            settings.javaClass.getMethod("setEditorReviewEnabled", Boolean::class.javaPrimitiveType).invoke(settings, false)
            patrulha.ideEditorReviewBefore = before
        }.onFailure { LOG.warn("O modo revisão do plugin de GitHub não pôde ser desligado", it) }
    }

    fun restore(project: Project) {
        val patrulha = PatrulhaService.getInstance(project)
        val before = patrulha.ideEditorReviewBefore ?: return
        patrulha.ideEditorReviewBefore = null
        val settings = settings(project) ?: return
        runCatching { settings.javaClass.getMethod("setEditorReviewEnabled", Boolean::class.javaPrimitiveType).invoke(settings, before) }
            .onFailure { LOG.warn("O modo revisão do plugin de GitHub não pôde voltar", it) }
    }

    private fun settings(project: Project): Any? =
        runCatching { project.getService(Class.forName(SETTINGS, true, IdeEditorReview::class.java.classLoader)) }
            .onFailure { LOG.info("Sem a configuração do modo revisão do plugin de GitHub nesta IDE", it) }
            .getOrNull()
}
