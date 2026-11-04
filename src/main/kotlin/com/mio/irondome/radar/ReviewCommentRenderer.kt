package com.mio.irondome.radar

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.Tone
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * Cartão do comentário, desenhado abaixo das linhas comentadas, como no review do GitHub, ou no
 * topo do arquivo, quando o alvo é o arquivo inteiro.
 * Resolvidos aparecem recolhidos numa barra de uma linha; o cabeçalho alterna e o Ocultar tira do editor,
 * de forma reversível pela aba Radar.
 * Pendentes e não salvos têm o botão Editar. Um [card] de outro mecanismo se comporta como resolvido, com
 * o selo e as ações dele.
 */
class ReviewCommentRenderer(
    val comment: ReviewComment,
    markedForRemoval: Boolean,
    unsaved: Boolean,
    private val indentPx: Int,
    val card: EditorCard? = null,
) : EditorCustomElementRenderer {

    enum class Hit { NONE, TOGGLE, HIDE, EDIT, FIRE }

    private val status = when {
        card != null -> CommentStatus.RESOLVED
        markedForRemoval -> CommentStatus.REMOVING
        unsaved -> CommentStatus.UNSAVED
        comment.resolved -> CommentStatus.RESOLVED
        else -> CommentStatus.PENDING
    }
    private val resolved = status == CommentStatus.RESOLVED
    private val editable = card == null && (status == CommentStatus.PENDING || status == CommentStatus.UNSAVED)
    /** Da direita para a esquerda no cabeçalho. */
    private val buttons: List<Pair<String, Hit>> = when {
        card != null -> listOfNotNull(HIDE_LABEL to Hit.HIDE, (EDIT_LABEL to Hit.EDIT).takeIf { card.onEdit != null })
        resolved -> listOf(HIDE_LABEL to Hit.HIDE)
        // Alvos do Radar disparam um a um; os de PR vão para o GitHub pela Patrulha.
        editable && comment.pullRequest == 0 -> listOf(EDIT_LABEL to Hit.EDIT, FIRE_LABEL to Hit.FIRE)
        editable -> listOf(EDIT_LABEL to Hit.EDIT)
        else -> emptyList()
    }
    private val collapsed = resolved && !comment.expanded
    private val modeLabel: String? = if (comment.discuss && !resolved) DISCUSS_LABEL else null
    private val title = comment.id
    /** A gravidade do início do texto vira etiqueta no cabeçalho e sai do corpo. */
    private val severity: Severity? = CommentMarkdown.severity(comment.text)
    private val blocks: List<Block> = CommentMarkdown.parse(if (severity != null) CommentMarkdown.withoutSeverity(comment.text) else comment.text)
    private var layout: Pair<Int, CommentBody>? = null
    private val headerText =
        if (collapsed) CommentMarkdown.plain(comment.text, SUMMARY_MAX)
        else if (card != null) card.subtitle
        else if (comment.pullRequest != 0) "Pedido para o PR #${comment.pullRequest}${if (comment.target == CommentTarget.FILE) " · arquivo inteiro" else ""}"
        else if (comment.origin == CommentOrigin.ESCUDO) "Alvo marcado pelo Escudo · ${ReviewCommentService.reviewName(comment.reviewId)}"
        else if (comment.target == CommentTarget.FILE) "Alvo: arquivo inteiro · ${ReviewCommentService.reviewName(comment.reviewId)}"
        else "Alvo para a IA · ${ReviewCommentService.reviewName(comment.reviewId)}"
    /** Quem marcou, quando não foi o usuário. */
    private val originLabel: String? = comment.origin.takeIf { card == null && it != CommentOrigin.RADAR }?.label
    private val unsentToPullRequest = comment.pullRequest != 0 && status == CommentStatus.UNSAVED
    private val statusLabel = card?.badge ?: if (unsentToPullRequest) PULL_REQUEST_UNSENT else status.label
    private val accent: Color = card?.tone?.accent ?: if (unsentToPullRequest) ReviewColors.pullRequestAccent else status.accent
    private val background: Color =
        if (card != null || unsentToPullRequest) ReviewColors.pullRequestBackground else status.background
    private val badgeBackground: Color =
        card?.tone?.background ?: if (unsentToPullRequest) ReviewColors.pullRequestBadgeBackground else status.badgeBackground
    private val badgeForeground: Color =
        card?.tone?.foreground ?: if (unsentToPullRequest) ReviewColors.pullRequestBadgeForeground else status.badgeForeground
    private val icon: Icon = when {
        comment.pullRequest != 0 -> IronDomeIcons.patrulha
        comment.origin == CommentOrigin.ESCUDO -> IronDomeIcons.escudo
        else -> ReviewIcons.comment
    }
    private val suggestion: List<String> =
        if (comment.pullRequest != 0 && comment.suggestion.isNotBlank()) comment.suggestion.trimEnd().lines() else emptyList()
    private val chevron: Icon? = when {
        !resolved -> null
        collapsed -> AllIcons.General.ArrowRight
        else -> AllIcons.General.ArrowDown
    }

    private class Fonts(editor: Editor) {
        private val component = editor.contentComponent
        val title: Font = JBFont.label().asBold()
        val subtitle: Font = JBFont.label()
        val badge: Font = JBFont.small().asBold()
        val button: Font = JBFont.small().asBold()
        val body = BodyFonts(editor)
        val code: Font = body.code
        val titleMetrics: FontMetrics = component.getFontMetrics(title)
        val subtitleMetrics: FontMetrics = component.getFontMetrics(subtitle)
        val badgeMetrics: FontMetrics = component.getFontMetrics(badge)
        val buttonMetrics: FontMetrics = component.getFontMetrics(button)
        val codeMetrics: FontMetrics = body.codeMetrics

        val badgeHeight = badgeMetrics.height + JBUI.scale(2)
        val buttonHeight = buttonMetrics.height + JBUI.scale(4)
        val headerHeight = maxOf(ReviewIcons.comment.iconHeight, titleMetrics.height, badgeHeight, buttonHeight)
        val bodyLineHeight = body.lineHeight
        val codeLineHeight = body.codeLineHeight
    }

    /** O corpo diagramado na largura que o editor dá, guardado enquanto essa largura não muda. */
    private fun body(editor: Editor, fonts: Fonts): CommentBody {
        val width = wrapWidth(editor)
        layout?.takeIf { it.first == width }?.let { return it.second }
        return CommentBody(blocks, fonts.body, width).also { layout = width to it }
    }

    /** Até onde o texto corre antes de quebrar: cabe na área visível, entre um mínimo legível e um máximo confortável. */
    private fun wrapWidth(editor: Editor): Int {
        val visible = editor.scrollingModel.visibleArea.width
        if (visible <= 0) return JBUI.scale(640)
        val available = visible - indentPx - accentWidth() - 2 * padding() - JBUI.scale(32)
        return available.coerceIn(JBUI.scale(320), JBUI.scale(760))
    }

    private class Geometry(val card: Rectangle, val header: Rectangle, val headerCenter: Int, val buttons: List<Pair<Rectangle, Pair<String, Hit>>>)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fonts = Fonts(inlay.editor)
        val header = (chevron?.let { it.iconWidth + gap() } ?: 0) +
            icon.iconWidth + gap() +
            fonts.titleMetrics.stringWidth(title) + gap() +
            (severity?.let { badgeWidth(fonts, it.label) + gap() } ?: 0) +
            fonts.subtitleMetrics.stringWidth(headerText) + 2 * gap() +
            badgeWidth(fonts, statusLabel) +
            (modeLabel?.let { gap() + badgeWidth(fonts, it) } ?: 0) +
            (originLabel?.let { gap() + badgeWidth(fonts, it) } ?: 0) +
            (if (buttons.isNotEmpty()) gap() + buttons.sumOf { gap() + buttonWidth(fonts, it.first) } else 0)
        val text = if (collapsed) 0 else body(inlay.editor, fonts).naturalWidth
        val code = suggestion.maxOfOrNull { fonts.codeMetrics.stringWidth(it.expandTabs()) }?.let { it + 2 * padding() } ?: 0
        return indentPx + accentWidth() + 2 * padding() + maxOf(header, text, code, JBUI.scale(320))
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val fonts = Fonts(inlay.editor)
        if (collapsed) return 2 * margin() + 2 * verticalPadding() + fonts.headerHeight
        return 2 * margin() + 2 * padding() + fonts.headerHeight + 2 * separatorGap() + 1 +
            body(inlay.editor, fonts).height + suggestionHeight(fonts)
    }

    private fun suggestionHeight(fonts: Fonts): Int =
        if (suggestion.isEmpty()) 0
        else separatorGap() + fonts.bodyLineHeight + gap() + 2 * codePadding() + suggestion.size * fonts.codeLineHeight

    /** [point] em coordenadas do `contentComponent` do editor, como [Inlay.getBounds]. */
    fun hitTest(inlay: Inlay<*>, point: Point): Hit {
        if (buttons.isEmpty()) return Hit.NONE
        val bounds = inlay.bounds ?: return Hit.NONE
        val geometry = geometry(bounds, Fonts(inlay.editor))
        geometry.buttons.firstOrNull { it.first.contains(point) }?.let { return it.second.second }
        return if (resolved && geometry.header.contains(point)) Hit.TOGGLE else Hit.NONE
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val fonts = Fonts(editor)
        val geometry = geometry(targetRegion, fonts)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val x = geometry.card.x
            val y = geometry.card.y
            val width = geometry.card.width
            val height = geometry.card.height
            val arc = JBUI.scale(10).toFloat()
            val card = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), arc, arc)

            val oldClip = g2.clip
            g2.clip(card)
            g2.color = background
            g2.fill(card)
            g2.color = accent
            g2.fillRect(x, y, accentWidth(), height + 1)
            g2.clip = oldClip

            g2.color = ReviewColors.cardBorder
            if (status == CommentStatus.UNSAVED) {
                val dash = JBUI.scale(4).toFloat()
                g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(dash, dash), 0f)
                g2.color = accent
            }
            g2.draw(card)
            g2.stroke = BasicStroke()

            val left = x + accentWidth() + padding()
            val headerCenter = geometry.headerCenter
            var cursor = left

            chevron?.let {
                it.paintIcon(editor.contentComponent, g2, cursor, headerCenter - it.iconHeight / 2)
                cursor += it.iconWidth + gap()
            }

            icon.paintIcon(editor.contentComponent, g2, cursor, headerCenter - icon.iconHeight / 2)
            cursor += icon.iconWidth + gap()

            g2.font = fonts.title
            g2.color = editor.colorsScheme.defaultForeground
            g2.drawString(title, cursor, baselineFor(headerCenter, fonts.titleMetrics))
            cursor += fonts.titleMetrics.stringWidth(title) + gap()

            severity?.let {
                paintBadge(g2, fonts, cursor, headerCenter, it.label, it.tone.background, it.tone.foreground)
                cursor += badgeWidth(fonts, it.label) + gap()
            }

            g2.font = fonts.subtitle
            g2.color = ReviewColors.mutedForeground
            g2.drawString(headerText, cursor, baselineFor(headerCenter, fonts.subtitleMetrics))
            cursor += fonts.subtitleMetrics.stringWidth(headerText) + 2 * gap()

            paintBadge(g2, fonts, cursor, headerCenter, statusLabel, badgeBackground, badgeForeground)
            cursor += badgeWidth(fonts, statusLabel) + gap()
            modeLabel?.let {
                paintBadge(
                    g2, fonts, cursor, headerCenter, it,
                    ReviewColors.discussBadgeBackground, ReviewColors.discussBadgeForeground,
                )
                cursor += badgeWidth(fonts, it) + gap()
            }
            originLabel?.let {
                paintBadge(g2, fonts, cursor, headerCenter, it, Tone.GREEN.background, Tone.GREEN.foreground)
            }
            geometry.buttons.forEach { (rect, button) ->
                paintButton(g2, fonts, rect, button.first, editor.colorsScheme.defaultForeground)
            }

            if (collapsed) return

            val separatorY = geometry.header.y + geometry.header.height
            g2.color = ReviewColors.cardBorder
            g2.drawLine(x + accentWidth(), separatorY, x + width, separatorY)

            val removing = status == CommentStatus.REMOVING
            val palette = BodyPalette(
                foreground = if (removing) ReviewColors.mutedForeground else editor.colorsScheme.defaultForeground,
                muted = ReviewColors.mutedForeground,
                label = if (removing) ReviewColors.mutedForeground else severity?.tone?.foreground ?: badgeForeground,
                bullet = if (removing) ReviewColors.mutedForeground else accent,
                codeBackground = ReviewColors.codeBackground,
                border = ReviewColors.cardBorder,
                strikeout = removing,
            )
            val body = body(editor, fonts)
            val lineTop = separatorY + 1 + separatorGap()
            body.paint(g2, left, lineTop, x + width - padding() - left, palette)
            if (suggestion.isNotEmpty()) paintSuggestion(g2, fonts, editor, left, lineTop + body.height + separatorGap(), x + width - padding() - left)
        } finally {
            g2.dispose()
        }
    }

    private fun geometry(region: Rectangle, fonts: Fonts): Geometry {
        val card = Rectangle(
            region.x + indentPx,
            region.y + margin(),
            region.width - indentPx - 1,
            region.height - 2 * margin() - 1,
        )
        val headerTop = card.y + if (collapsed) verticalPadding() else padding()
        val headerCenter = headerTop + fonts.headerHeight / 2
        val header =
            if (collapsed) Rectangle(card)
            else Rectangle(card.x, card.y, card.width, headerTop + fonts.headerHeight + separatorGap() - card.y)
        var right = card.x + card.width - padding()
        val placed = buttons.map { button ->
            val width = buttonWidth(fonts, button.first)
            right -= width
            (Rectangle(right, headerCenter - fonts.buttonHeight / 2, width, fonts.buttonHeight) to button).also { right -= gap() }
        }
        return Geometry(card, header, headerCenter, placed)
    }

    private fun paintSuggestion(g2: Graphics2D, fonts: Fonts, editor: Editor, left: Int, top: Int, width: Int) {
        g2.font = fonts.subtitle
        g2.color = ReviewColors.mutedForeground
        val label = if (comment.target == CommentTarget.LINES) SUGGESTION_LABEL else EXAMPLE_LABEL
        g2.drawString(label, left, top + (fonts.bodyLineHeight - fonts.subtitleMetrics.height) / 2 + fonts.subtitleMetrics.ascent)
        val boxTop = top + fonts.bodyLineHeight + gap()
        val boxHeight = 2 * codePadding() + suggestion.size * fonts.codeLineHeight
        val arc = JBUI.scale(6)
        g2.color = ReviewColors.codeBackground
        g2.fillRoundRect(left, boxTop, width, boxHeight, arc, arc)
        g2.color = ReviewColors.cardBorder
        g2.drawRoundRect(left, boxTop, width, boxHeight, arc, arc)
        g2.font = fonts.code
        g2.color = editor.colorsScheme.defaultForeground
        var baseline = boxTop + codePadding() + fonts.codeMetrics.ascent
        for (line in suggestion) {
            g2.drawString(line.expandTabs(), left + padding(), baseline)
            baseline += fonts.codeLineHeight
        }
    }

    private fun String.expandTabs() = replace("\t", "    ")

    private fun paintBadge(
        g2: Graphics2D,
        fonts: Fonts,
        x: Int,
        centerY: Int,
        label: String,
        background: Color,
        foreground: Color,
    ) {
        val width = badgeWidth(fonts, label)
        val height = fonts.badgeHeight
        val top = centerY - height / 2
        g2.color = background
        g2.fillRoundRect(x, top, width, height, height, height)
        g2.font = fonts.badge
        g2.color = foreground
        g2.drawString(label, x + badgePadding(), baselineFor(centerY, fonts.badgeMetrics))
    }

    private fun paintButton(g2: Graphics2D, fonts: Fonts, button: Rectangle, label: String, foreground: Color) {
        val arc = JBUI.scale(8)
        g2.color = ReviewColors.buttonBackground
        g2.fillRoundRect(button.x, button.y, button.width, button.height, arc, arc)
        g2.color = ReviewColors.cardBorder
        g2.drawRoundRect(button.x, button.y, button.width, button.height, arc, arc)
        g2.font = fonts.button
        g2.color = foreground
        val textX = button.x + (button.width - fonts.buttonMetrics.stringWidth(label)) / 2
        g2.drawString(label, textX, baselineFor(button.y + button.height / 2, fonts.buttonMetrics))
    }

    private fun buttonWidth(fonts: Fonts, label: String) = fonts.buttonMetrics.stringWidth(label) + 2 * JBUI.scale(12)

    private fun badgeWidth(fonts: Fonts, label: String) = fonts.badgeMetrics.stringWidth(label) + 2 * badgePadding()

    private fun baselineFor(centerY: Int, metrics: FontMetrics) = centerY - metrics.height / 2 + metrics.ascent

    private fun accentWidth() = JBUI.scale(4)

    private fun padding() = JBUI.scale(10)

    private fun verticalPadding() = JBUI.scale(5)

    private fun gap() = JBUI.scale(6)

    private fun badgePadding() = JBUI.scale(7)

    private fun separatorGap() = JBUI.scale(7)

    private fun margin() = JBUI.scale(4)

    private fun codePadding() = JBUI.scale(6)

    private companion object {
        const val HIDE_LABEL = "Ocultar"
        const val EDIT_LABEL = "Editar"
        const val FIRE_LABEL = "Disparar"
        const val DISCUSS_LABEL = "Discutir antes"
        const val PULL_REQUEST_UNSENT = "Não enviado"
        const val SUGGESTION_LABEL = "Código sugerido (substitui as linhas comentadas):"
        const val EXAMPLE_LABEL = "Código de exemplo:"
        const val SUMMARY_MAX = 80
    }
}
