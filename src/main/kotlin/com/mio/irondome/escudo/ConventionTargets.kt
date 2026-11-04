package com.mio.irondome.escudo

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.plural
import com.mio.irondome.interceptor.AgentSettings
import com.mio.irondome.interceptor.FireWithAiAction
import com.mio.irondome.radar.CommentOrigin
import com.mio.irondome.radar.ReviewCommentService

/**
 * Deixar no Padrão: cada classe de teste fora da convenção vira um alvo no Radar, marcado como do Escudo,
 * na linha do `@DisplayName` (sem chave) ou da declaração da classe (sem `@DisplayName`). A IA corrige só o
 * `@DisplayName` quando a ordem de interceptação é disparada.
 */
object ConventionTargets {

    private const val DISPLAY_NAME = "org.junit.jupiter.api.DisplayName"

    /** O pedido para a IA; [keys] são as chaves que o projeto já usa, para ela reaproveitar. */
    fun text(className: String, convention: Convention, label: String, keys: List<String>): String = buildString {
        when (convention) {
            Convention.MISSING_KEY -> {
                append("O @DisplayName(\"$label\") da classe de teste `$className` está sem a chave. ")
                append("Deixe no padrão do Escudo: @DisplayName(\"[Chave] $label\").")
            }
            else -> {
                append("A classe de teste `$className` está sem @DisplayName. ")
                append("Coloque na classe @DisplayName(\"[Chave] Descrição\"), com a descrição dizendo em português o que ela testa.")
            }
        }
        append("\n\nA chave entre colchetes agrupa no Escudo as classes do mesmo assunto. ")
        if (keys.isEmpty()) {
            append("Crie uma chave curta para o assunto da classe.")
        } else {
            append("Reaproveite uma das chaves que o projeto já usa, se o assunto for o mesmo: ")
            append(keys.joinToString(", ") { "[$it]" })
            append("; senão, crie uma curta.")
        }
        append(" Altere só o @DisplayName da classe, sem mexer nos testes, e adicione o import de org.junit.jupiter.api.DisplayName se faltar.")
    }

    /** Só pela EDT. */
    fun mark(project: Project, classes: List<TestNode>, keys: List<String>) {
        if (DumbService.isDumb(project)) {
            notify(project, "Aguarde a indexação do projeto terminar para deixar os testes no padrão.", NotificationType.WARNING)
            return
        }
        val service = project.service<ReviewCommentService>()
        var created = 0
        var existing = 0
        val missing = mutableListOf<String>()
        for (testClass in classes) {
            val convention = testClass.convention ?: continue
            if (convention == Convention.OK) continue
            val className = testClass.codeReference?.substringAfterLast('.')?.substringAfterLast('$') ?: testClass.label
            val place = testClass.locationUrl?.let { url -> ReadAction.compute<Place?, RuntimeException> { placeOf(project, url) } }
            if (place == null) {
                missing += className
                continue
            }
            val path = service.relativePath(place.file)
            if (path == null) {
                missing += className
                continue
            }
            if (service.hasOpenComment(path, CommentOrigin.ESCUDO, "`$className`")) {
                existing++
                continue
            }
            val comment = service.addComment(
                place.file,
                place.document,
                place.line..place.line,
                text(className, convention, testClass.label, keys),
                discuss = false,
                origin = CommentOrigin.ESCUDO,
            )
            if (comment != null) created++ else missing += className
        }
        report(project, created, existing, missing)
    }

    private class Place(val file: VirtualFile, val document: Document, val line: Int)

    /** A linha do `@DisplayName` da classe, se houver; senão, a do nome da classe. */
    private fun placeOf(project: Project, url: String): Place? {
        val owner = JUnitDisplayNames.resolve(project, url) ?: return null
        val annotation = AnnotationUtil.findAnnotation(owner, DISPLAY_NAME)?.navigationElement
        val declaration = owner.navigationElement
        val anchor: PsiElement = annotation ?: (declaration as? PsiNameIdentifierOwner)?.nameIdentifier ?: declaration
        val file = anchor.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = anchor.textRange?.startOffset ?: return null
        if (offset > document.textLength) return null
        return Place(file, document, document.getLineNumber(offset))
    }

    private fun report(project: Project, created: Int, existing: Int, missing: List<String>) {
        val parts = listOfNotNull(
            plural(created, "alvo marcado", "alvos marcados").takeIf { created > 0 }?.let { "$it no Radar, como do Escudo" },
            plural(existing, "classe já tinha alvo aberto", "classes já tinham alvo aberto").takeIf { existing > 0 },
            missing.takeIf { it.isNotEmpty() }?.let { "não achei no código: ${it.joinToString(", ")}" },
        )
        val type = if (created > 0) NotificationType.INFORMATION else NotificationType.WARNING
        val content = parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "." +
            if (created > 0) " Dispare a ordem de interceptação para a IA deixar o @DisplayName no padrão." else ""
        val actions = buildList {
            add(NotificationAction.createSimpleExpiring("Abrir Radar") { ReviewToolWindowFactory.showTab(project, ReviewToolWindowFactory.RADAR) })
            if (created > 0 && AgentSettings.ready) add(NotificationAction.createSimpleExpiring("Disparar com IA") { FireWithAiAction.fire(project) })
        }
        notify(project, content, type, actions)
    }

    private fun notify(project: Project, content: String, type: NotificationType, actions: List<NotificationAction> = emptyList()) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification("Escudo: deixar no padrão", content, type)
            .setIcon(IronDomeIcons.escudo)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }
}
