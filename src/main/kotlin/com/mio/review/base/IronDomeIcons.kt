package com.mio.review.base

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Um ícone colorido por mecanismo de defesa, usado nas abas da tool window. */
object IronDomeIcons {
    val radar: Icon = IconLoader.getIcon("/icons/radar.svg", IronDomeIcons::class.java)
    val sentinela: Icon = IconLoader.getIcon("/icons/sentinela.svg", IronDomeIcons::class.java)
    val impacto: Icon = IconLoader.getIcon("/icons/impacto.svg", IronDomeIcons::class.java)
}
