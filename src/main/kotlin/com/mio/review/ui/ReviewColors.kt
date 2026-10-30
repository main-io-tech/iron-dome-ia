package com.mio.review.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.Icon

/** Estado visual de um comentário: define a etiqueta e as cores do cartão no editor. */
enum class CommentStatus(
    val label: String,
    val accent: JBColor,
    val background: JBColor,
    val badgeBackground: JBColor,
    val badgeForeground: JBColor,
) {
    PENDING(
        "Pendente",
        JBColor(Color(0xD4A72C), Color(0xBB8009)),
        JBColor(Color(0xFFFCF2), Color(0x2B2922)),
        JBColor(Color(0xFFF1C2), Color(0x4A3D12)),
        JBColor(Color(0x7A5A00), Color(0xE3C065)),
    ),
    UNSAVED(
        "Não salvo",
        JBColor(Color(0x0969DA), Color(0x2F81F7)),
        JBColor(Color(0xF5FAFF), Color(0x222A33)),
        JBColor(Color(0xDDF4FF), Color(0x12304F)),
        JBColor(Color(0x0550AE), Color(0x79C0FF)),
    ),
    RESOLVED(
        "Resolvido",
        JBColor(Color(0x1A7F37), Color(0x3FB950)),
        JBColor(Color(0xF4FBF5), Color(0x222B24)),
        JBColor(Color(0xDAFBE1), Color(0x13361F)),
        JBColor(Color(0x116329), Color(0x56D364)),
    ),
    REMOVING(
        "Será removido ao salvar",
        JBColor(Color(0xCF222E), Color(0xF85149)),
        JBColor(Color(0xFAFAFA), Color(0x2A2A2C)),
        JBColor(Color(0xFFEBE9), Color(0x4A1A1E)),
        JBColor(Color(0xA40E26), Color(0xFF7B72)),
    ),
}

object ReviewColors {
    val cardBorder = JBColor(Color(0xD0D7DE), Color(0x3D444D))
    val mutedForeground = JBColor(Color(0x656D76), Color(0x8D96A0))
    val buttonBackground = JBColor(Color(0xF6F8FA), Color(0x2D333B))
    val discussBadgeBackground = JBColor(Color(0xFBEFFF), Color(0x3A2350))
    val discussBadgeForeground = JBColor(Color(0x8250DF), Color(0xD2A8FF))
}

object ReviewIcons {
    val addComment: Icon = IconLoader.getIcon("/icons/addComment.svg", ReviewIcons::class.java)
    val removeComment: Icon = IconLoader.getIcon("/icons/removeComment.svg", ReviewIcons::class.java)
    val undoRemove: Icon = IconLoader.getIcon("/icons/undoRemove.svg", ReviewIcons::class.java)
    val comment: Icon = IconLoader.getIcon("/icons/comment.svg", ReviewIcons::class.java)
}
