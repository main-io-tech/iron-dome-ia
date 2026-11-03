package com.mio.irondome.radar

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.mio.irondome.base.Tone
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
        "Na mira",
        JBColor(Color(0xD4A72C), Color(0xBB8009)),
        JBColor(Color(0xFFFCF2), Color(0x2B2922)),
        JBColor(Color(0xFFF1C2), Color(0x4A3D12)),
        JBColor(Color(0x7A5A00), Color(0xE3C065)),
    ),
    UNSAVED(
        "Não disparado",
        JBColor(Color(0x0969DA), Color(0x2F81F7)),
        JBColor(Color(0xF5FAFF), Color(0x222A33)),
        JBColor(Color(0xDDF4FF), Color(0x12304F)),
        JBColor(Color(0x0550AE), Color(0x79C0FF)),
    ),
    RESOLVED(
        "Interceptado",
        JBColor(Color(0x1A7F37), Color(0x3FB950)),
        JBColor(Color(0xF4FBF5), Color(0x222B24)),
        JBColor(Color(0xDAFBE1), Color(0x13361F)),
        JBColor(Color(0x116329), Color(0x56D364)),
    ),
    REMOVING(
        "Cancelado no próximo disparo",
        JBColor(Color(0xCF222E), Color(0xF85149)),
        JBColor(Color(0xFAFAFA), Color(0x2A2A2C)),
        JBColor(Color(0xFFEBE9), Color(0x4A1A1E)),
        JBColor(Color(0xA40E26), Color(0xFF7B72)),
    );

    val tone: Tone get() = Tone(badgeForeground, badgeBackground, accent)
}

object ReviewColors {
    val cardBorder = JBColor(Color(0xD0D7DE), Color(0x3D444D))
    val mutedForeground = JBColor(Color(0x656D76), Color(0x8D96A0))
    val buttonBackground = JBColor(Color(0xF6F8FA), Color(0x2D333B))
    val discussBadgeBackground = JBColor(Color(0xFBEFFF), Color(0x3A2350))
    val discussBadgeForeground = JBColor(Color(0x8250DF), Color(0xD2A8FF))
    val discussTone = Tone(discussBadgeForeground, discussBadgeBackground, discussBadgeForeground)

    /** Comentários que vão para um PR, pela Patrulha, no lugar do azul dos que vão para a IA. */
    val pullRequestAccent = JBColor(Color(0x8250DF), Color(0xA371F7))
    val pullRequestBackground = JBColor(Color(0xFBF8FF), Color(0x2A2433))
    val pullRequestBadgeBackground = JBColor(Color(0xEFE3FF), Color(0x3A2350))
    val pullRequestBadgeForeground = JBColor(Color(0x6639BA), Color(0xD2A8FF))
    val codeBackground = JBColor(Color(0xF6F8FA), Color(0x22272E))
}

object ReviewIcons {
    val addComment: Icon = IconLoader.getIcon("/icons/addComment.svg", ReviewIcons::class.java)
    /** No lugar do [addComment] enquanto um PR está em revisão pela Patrulha. */
    val addPullRequestComment: Icon = IconLoader.getIcon("/icons/addPrComment.svg", ReviewIcons::class.java)
    val removeComment: Icon = IconLoader.getIcon("/icons/removeComment.svg", ReviewIcons::class.java)
    val undoRemove: Icon = IconLoader.getIcon("/icons/undoRemove.svg", ReviewIcons::class.java)
    val comment: Icon = IconLoader.getIcon("/icons/comment.svg", ReviewIcons::class.java)
}
