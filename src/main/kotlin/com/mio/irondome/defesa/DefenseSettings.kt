package com.mio.irondome.defesa

import com.intellij.ide.util.PropertiesComponent

/** A inteligência dos pedidos à IA e dos avisos de revisão: cada parte pode ser desligada à parte, nas configurações. */
object DefenseSettings {

    private const val PREFIX = "IronDome.Defesa."

    /** Aprender com o que o time aceita e descarta das sugestões da IA, e ajustar o pedido. */
    var feedbackLearning: Boolean by flag("FeedbackLearning", true)

    /** Avisar quando dois comentários pendentes apontam para o mesmo trecho e oferecer a síntese pela IA. */
    var conflictDetection: Boolean by flag("ConflictDetection", true)

    /** Anexar aos pedidos à IA o dossiê da classe: comentários antigos, correções do git e decisões registradas. */
    var dossier: Boolean by flag("Dossier", true)

    private fun flag(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean =
            PropertiesComponent.getInstance().getBoolean(PREFIX + key, default)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            PropertiesComponent.getInstance().setValue(PREFIX + key, value, default)
    }
}
