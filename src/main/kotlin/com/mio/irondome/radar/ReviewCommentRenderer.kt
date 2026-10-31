package com.mio.irondome.radar

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
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
 * Resolvidos aparecem recolhidos numa barra de uma linha; o cabeçalho alterna e o OK dispensa.
 * Pendentes e não salvos têm o botão Editar.
 */
class ReviewCommentRenderer(
    val comment: ReviewComment,
    markedForRemoval: Boolean,
    unsaved: Boolean,
    private val indentPx: Int,
) : EditorCustomElementRenderer {

    enum class Hit { NONE, TOGGLE, DISMISS, EDIT }

    private val status = when {
        markedForRemoval -> CommentStatus.REMOVING
        unsaved -> CommentStatus.UNSAVED
        comment.resolved -> CommentStatus.RESOLVED
        else -> CommentStatus.PENDING
    }
    private val resolved = status == CommentStatus.RESOLVED
    private val editable = status == CommentStatus.PENDING || status == CommentStatus.UNSAVED
    private val buttonLabel: String? = when {
        resolved -> OK_LABEL
        editable -> EDIT_LABEL
        else -> null
    }
    private val collapsed = resolved && !comment.expanded
    private val modeLabel: String? = if (comment.discuss && !resolved) DISCUSS_LABEL else null
    private val title = comment.id
    private val body = comment.text.trim().lines()
    private val headerText =
        if (collapsed) summaryOf(body)
        else if (comment.target == CommentTarget.FILE) "Alvo: arquivo inteiro · ${ReviewCommentService.reviewName(comment.reviewId)}"
        else "Alvo para a IA · ${ReviewCommentService.reviewName(comment.reviewId)}"
    private val chevron: Icon? = when {
        !resolved -> null
        collapsed -> AllIcons.General.ArrowRight
        else -> AllIcons.General.ArrowDown
    }

    private class Fonts(component: Component) {
        val title: Font = JBFont.label().asBold()
        val subtitle: Font = JBFont.label()
        val badge: Font = JBFont.small().asBold()
        val button: Font = JBFont.small().asBold()
        val body: Font = JBFont.label()
        val titleMetrics: FontMetrics = component.getFontMetrics(title)
        val subtitleMetrics: FontMetrics = component.getFontMetrics(subtitle)
        val badgeMetrics: FontMetrics = component.getFontMetrics(badge)
        val buttonMetrics: FontMetrics = component.getFontMetrics(button)
        val bodyMetrics: FontMetrics = component.getFontMetrics(body)

        val badgeHeight = badgeMetrics.height + JBUI.scale(2)
        val buttonHeight = buttonMetrics.height + JBUI.scale(4)
        val headerHeight = maxOf(ReviewIcons.comment.iconHeight, titleMetrics.height, badgeHeight, buttonHeight)
        val bodyLineHeight = bodyMetrics.height + JBUI.scale(2)
    }

    private class Geometry(val card: Rectangle, val header: Rectangle, val headerCenter: Int, val button: Rectangle?)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fonts = Fonts(inlay.editor.contentComponent)
        val header = (chevron?.let { it.iconWidth + gap() } ?: 0) +
            ReviewIcons.comment.iconWidth + gap() +
            fonts.titleMetrics.stringWidth(title) + gap() +
            fonts.subtitleMetrics.stringWidth(headerText) + 2 * gap() +
            badgeWidth(fonts, status.label) +
            (modeLabel?.let { gap() + badgeWidth(fonts, it) } ?: 0) +
            (if (buttonLabel != null) 2 * gap() + buttonWidth(fonts, buttonLabel) else 0)
        val text = if (collapsed) 0 else body.maxOfOrNull { fonts.bodyMetrics.stringWidth(it) } ?: 0
        return indentPx + accentWidth() + 2 * padding() + maxOf(header, text, JBUI.scale(320))
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val fonts = Fonts(inlay.editor.contentComponent)
        if (collapsed) return 2 * margin() + 2 * verticalPadding() + fonts.headerHeight
        return 2 * margin() + 2 * padding() + fonts.headerHeight + 2 * separatorGap() + 1 +
            body.size * fonts.bodyLineHeight
    }

    /** [point] em coordenadas do `contentComponent` do editor, como [Inlay.getBounds]. */
    fun hitTest(inlay: Inlay<*>, point: Point): Hit {
        if (buttonLabel == null) return Hit.NONE
        val bounds = inlay.bounds ?: return Hit.NONE
        val geometry = geometry(bounds, Fonts(inlay.editor.contentComponent))
        return when {
            geometry.button?.contains(point) == true -> if (resolved) Hit.DISMISS else Hit.EDIT
            resolved && geometry.header.contains(point) -> Hit.TOGGLE
            else -> Hit.NONE
        }
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val fonts = Fonts(editor.contentComponent)
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
            g2.color = status.background
            g2.fill(card)
            g2.color = status.accent
            g2.fillRect(x, y, accentWidth(), height + 1)
            g2.clip = oldClip

            g2.color = ReviewColors.cardBorder
            if (status == CommentStatus.UNSAVED) {
                val dash = JBUI.scale(4).toFloat()
                g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(dash, dash), 0f)
                g2.color = status.accent
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

            val icon = ReviewIcons.comment
            icon.paintIcon(editor.contentComponent, g2, cursor, headerCenter - icon.iconHeight / 2)
            cursor += icon.iconWidth + gap()

            g2.font = fonts.title
            g2.color = editor.colorsScheme.defaultForeground
            g2.drawString(title, cursor, baselineFor(headerCenter, fonts.titleMetrics))
            cursor += fonts.titleMetrics.stringWidth(title) + gap()

            g2.font = fonts.subtitle
            g2.color = ReviewColors.mutedForeground
            g2.drawString(headerText, cursor, baselineFor(headerCenter, fonts.subtitleMetrics))
            cursor += fonts.subtitleMetrics.stringWidth(headerText) + 2 * gap()

            paintBadge(g2, fonts, cursor, headerCenter, status.label, status.badgeBackground, status.badgeForeground)
            cursor += badgeWidth(fonts, status.label) + gap()
            modeLabel?.let {
                paintBadge(
                    g2, fonts, cursor, headerCenter, it,
                    ReviewColors.discussBadgeBackground, ReviewColors.discussBadgeForeground,
                )
            }
            if (geometry.button != null && buttonLabel != null) {
                paintButton(g2, fonts, geometry.button, buttonLabel, editor.colorsScheme.defaultForeground)
            }

            if (collapsed) return

            val separatorY = geometry.header.y + geometry.header.height
            g2.color = ReviewColors.cardBorder
            g2.drawLine(x + accentWidth(), separatorY, x + width, separatorY)

            g2.font = fonts.body
            val removing = status == CommentStatus.REMOVING
            g2.color = if (removing) ReviewColors.mutedForeground else editor.colorsScheme.defaultForeground
            var lineTop = separatorY + 1 + separatorGap()
            for (line in body) {
                val baseline = lineTop + (fonts.bodyLineHeight - fonts.bodyMetrics.height) / 2 + fonts.bodyMetrics.ascent
                g2.drawString(line, left, baseline)
                if (removing && line.isNotEmpty()) {
                    val strikeY = baseline - fonts.bodyMetrics.ascent / 3
                    g2.drawLine(left, strikeY, left + fonts.bodyMetrics.stringWidth(line), strikeY)
                }
                lineTop += fonts.bodyLineHeight
            }
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
        val button = buttonLabel?.let { label ->
            val width = buttonWidth(fonts, label)
            Rectangle(card.x + card.width - padding() - width, headerCenter - fonts.buttonHeight / 2, width, fonts.buttonHeight)
        }
        return Geometry(card, header, headerCenter, button)
    }

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

    private companion object {
        const val OK_LABEL = "OK"
        const val EDIT_LABEL = "Editar"
        const val DISCUSS_LABEL = "Discutir antes"
        const val SUMMARY_MAX = 80

        fun summaryOf(body: List<String>): String {
            val first = body.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val more = first.length > SUMMARY_MAX || body.count { it.isNotBlank() } > 1
            return first.take(SUMMARY_MAX) + if (more) "…" else ""
        }
    }
}
