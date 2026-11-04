package com.mio.irondome.impacto

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.plural
import com.mio.irondome.defesa.DefenseStatus
import com.mio.irondome.radar.ReviewCommentService
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElement

/** Acima da classe salva: "Alterada · Raio: afeta 12 classes · 2 sem teste · risco alto". O clique abre o Raio de Impacto. */
class ImpactCodeVisionProvider : DaemonBoundCodeVisionProvider {

    override val id: String = ID
    override val name: String = NAME
    override val groupId: String = ID
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        val project = file.project
        val virtualFile = file.virtualFile ?: return emptyList()
        val path = project.service<ReviewCommentService>().relativePath(virtualFile) ?: return emptyList()
        val snapshot = DefenseStatus.getInstance(project).snapshotOf(path) ?: return emptyList()
        val uClass = (file.toUElement() as? UFile)?.classes?.firstOrNull { it.qualifiedName == snapshot.qualifiedName } ?: return emptyList()
        val range = uClass.uastAnchor?.sourcePsi?.textRange ?: uClass.sourcePsi?.textRange ?: return emptyList()
        val text = (if (snapshot.changed) "Alterada · " else "") + "Raio: ${snapshot.summary}"
        val tooltip = buildString {
            append("Risco ${snapshot.risk.explain().replaceFirstChar(Char::lowercase)}. ")
            append(
                if (snapshot.radiusTests.isEmpty()) "Nenhum teste cobre esta classe nem as que dependem dela."
                else "${plural(snapshot.radiusTests.size, "teste cobre", "testes cobrem")} o raio.",
            )
            append(" Clique para abrir o Raio de Impacto.")
        }
        val entry = ClickableTextCodeVisionEntry(
            text,
            ID,
            { _, _ -> DependencyTab.open(project) { it.analyze(snapshot.qualifiedName) } },
            IronDomeIcons.impacto,
            text,
            tooltip,
            emptyList(),
        )
        return listOf(range to entry)
    }

    companion object {
        const val ID = "irondome.impact"
        const val NAME = "Raio de Impacto (Iron Dome IA)"
    }
}

class ImpactCodeVisionSettings : CodeVisionGroupSettingProvider {
    override val groupId: String = ImpactCodeVisionProvider.ID
    override val groupName: String = ImpactCodeVisionProvider.NAME
    override val description: String =
        "Acima de cada classe salva, quantas classes dependem dela, quantas estão sem teste e o risco de alterá-la, com o histórico do git."
}
