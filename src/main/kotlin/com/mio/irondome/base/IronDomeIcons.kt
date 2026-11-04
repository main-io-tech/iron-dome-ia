package com.mio.irondome.base

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Um ícone colorido por mecanismo de defesa, usado nas abas da tool window. */
object IronDomeIcons {
    val radar: Icon = IconLoader.getIcon("/icons/radar.svg", IronDomeIcons::class.java)
    val sentinela: Icon = IconLoader.getIcon("/icons/sentinela.svg", IronDomeIcons::class.java)
    val impacto: Icon = IconLoader.getIcon("/icons/impacto.svg", IronDomeIcons::class.java)
    val escudo: Icon = IconLoader.getIcon("/icons/escudo.svg", IronDomeIcons::class.java)
    val caixaPreta: Icon = IconLoader.getIcon("/icons/caixaPreta.svg", IronDomeIcons::class.java)
    val patrulha: Icon = IconLoader.getIcon("/icons/patrulha.svg", IronDomeIcons::class.java)
    val interceptador: Icon = IconLoader.getIcon("/icons/interceptador.svg", IronDomeIcons::class.java)

    /** Os botões que chamam a IA, em qualquer aba. */
    val ia: Icon = IconLoader.getIcon("/icons/ia.svg", IronDomeIcons::class.java)
}
