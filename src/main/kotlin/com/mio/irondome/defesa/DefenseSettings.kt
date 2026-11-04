package com.mio.irondome.defesa

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/**
 * As defesas automáticas do Iron Dome: o que roda sozinho, sem ninguém pedir. Cada uma pode ser desligada à
 * parte, nas configurações. As que chamam a IA ficam desligadas por padrão, porque gastam tokens.
 */
object DefenseSettings {

    private const val PREFIX = "IronDome.Defesa."

    fun interface Listener {
        fun changed()

        companion object {
            @Topic.AppLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    /** Antes de cada commit: alvos pendentes, risco sem teste e co-mudanças quebradas nos arquivos commitados. */
    var commitGuard: Boolean by flag("CommitGuard", true)

    /** Ao salvar uma classe: o raio dela calculado em segundo plano, na status bar e acima da classe. */
    var passiveRadar: Boolean by flag("PassiveRadar", true)

    /** Quando uma classe muda: sugerir os testes que a cobrem, e às classes que dependem dela. */
    var shieldSuggest: Boolean by flag("ShieldSuggest", true)

    /** Rodar os testes do raio sozinho quando o código para de mudar. */
    var shieldAutoRun: Boolean by flag("ShieldAutoRun", false)

    /** Consultar o GitHub de tempos em tempos: comentários novos, CI quebrada e pedidos de revisão. */
    var patrolPolling: Boolean by flag("PatrolPolling", true)

    var patrolIntervalMinutes: Int
        get() = PropertiesComponent.getInstance().getInt(PREFIX + "PatrolInterval", DEFAULT_PATROL_MINUTES).coerceIn(1, 120)
        set(value) = change { PropertiesComponent.getInstance().setValue(PREFIX + "PatrolInterval", value.coerceIn(1, 120), DEFAULT_PATROL_MINUTES) }

    /** Ao entrar numa branch com PR aberto: trazer os comentários do PR para o editor sem passo manual. */
    var patrolAutoFetch: Boolean by flag("PatrolAutoFetch", true)

    /** Com as alterações paradas por [aiIdleMinutes]: a IA analisa o impacto em segundo plano, só leitura. */
    var aiBackgroundImpact: Boolean by flag("AiBackgroundImpact", false)

    var aiIdleMinutes: Int
        get() = PropertiesComponent.getInstance().getInt(PREFIX + "AiIdleMinutes", DEFAULT_IDLE_MINUTES).coerceIn(1, 120)
        set(value) = change { PropertiesComponent.getInstance().setValue(PREFIX + "AiIdleMinutes", value.coerceIn(1, 120), DEFAULT_IDLE_MINUTES) }

    var aiCooldownMinutes: Int
        get() = PropertiesComponent.getInstance().getInt(PREFIX + "AiCooldownMinutes", DEFAULT_COOLDOWN_MINUTES).coerceIn(1, 600)
        set(value) = change { PropertiesComponent.getInstance().setValue(PREFIX + "AiCooldownMinutes", value.coerceIn(1, 600), DEFAULT_COOLDOWN_MINUTES) }

    /** Quantas análises automáticas por dia, no máximo. */
    var aiDailyBudget: Int
        get() = PropertiesComponent.getInstance().getInt(PREFIX + "AiDailyBudget", DEFAULT_DAILY_BUDGET).coerceIn(1, 100)
        set(value) = change { PropertiesComponent.getInstance().setValue(PREFIX + "AiDailyBudget", value.coerceIn(1, 100), DEFAULT_DAILY_BUDGET) }

    /** Só chamar a IA quando a triagem achar que vale: diff só de imports ou formatação não vai. */
    var aiTriage: Boolean by flag("AiTriage", true)

    /** Aprender com o que o time aceita e descarta das sugestões da IA, e ajustar o pedido. */
    var feedbackLearning: Boolean by flag("FeedbackLearning", true)

    /** Avisar quando dois comentários pendentes apontam para o mesmo trecho e oferecer a síntese pela IA. */
    var conflictDetection: Boolean by flag("ConflictDetection", true)

    /** Anexar aos pedidos à IA o dossiê da classe: comentários antigos, correções do git e decisões registradas. */
    var dossier: Boolean by flag("Dossier", true)

    const val DEFAULT_PATROL_MINUTES = 5
    const val DEFAULT_IDLE_MINUTES = 5
    const val DEFAULT_COOLDOWN_MINUTES = 30
    const val DEFAULT_DAILY_BUDGET = 10

    private fun flag(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean =
            PropertiesComponent.getInstance().getBoolean(PREFIX + key, default)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            change { PropertiesComponent.getInstance().setValue(PREFIX + key, value, default) }
    }

    private fun change(update: () -> Unit) {
        update()
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(Listener.TOPIC)?.changed()
    }
}
