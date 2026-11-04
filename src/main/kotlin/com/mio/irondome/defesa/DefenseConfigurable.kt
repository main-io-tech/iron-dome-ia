package com.mio.irondome.defesa

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings > Tools > Iron Dome IA > Defesa Automática: o que roda sozinho, cada defesa desligável à parte. */
class DefenseConfigurable : Configurable {

    private val commitGuard = JBCheckBox("Conferir antes de cada commit")
    private val passiveRadar = JBCheckBox("Calcular o raio da classe ao salvar (status bar e acima da classe)")
    private val shieldSuggest = JBCheckBox("Sugerir os testes do raio quando uma classe muda")
    private val shieldAutoRun = JBCheckBox("Rodar os testes do raio sozinho quando o código para de mudar")
    private val patrolPolling = JBCheckBox("Consultar os PRs no GitHub de tempos em tempos")
    private val patrolInterval = JBIntSpinner(DefenseSettings.DEFAULT_PATROL_MINUTES, 1, 120)
    private val patrolAutoFetch = JBCheckBox("Trazer os comentários do PR ao entrar na branch dele")
    private val aiBackground = JBCheckBox("Analisar o impacto com a IA quando as alterações param (gasta tokens)")
    private val aiIdle = JBIntSpinner(DefenseSettings.DEFAULT_IDLE_MINUTES, 1, 120)
    private val aiCooldown = JBIntSpinner(DefenseSettings.DEFAULT_COOLDOWN_MINUTES, 1, 600)
    private val aiBudget = JBIntSpinner(DefenseSettings.DEFAULT_DAILY_BUDGET, 1, 100)
    private val aiTriage = JBCheckBox("Triagem: só chamar a IA quando o diff justificar")
    private val feedback = JBCheckBox("Aprender com o que o time aceita e descarta das sugestões da IA")
    private val dossier = JBCheckBox("Anexar aos pedidos o dossiê das classes (histórico, comentários antigos, decisões)")
    private val conflicts = JBCheckBox("Avisar quando dois comentários apontam para o mesmo trecho")

    override fun getDisplayName() = "Defesa Automática"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(TitledSeparator("Interceptação"))
        .addComponent(commitGuard)
        .addComponent(comment("Alvos pendentes, classes de risco alto sem teste alterado e arquivos que costumam mudar juntos."))
        .addComponent(passiveRadar)
        .addComponent(TitledSeparator("Escudo"))
        .addComponent(shieldSuggest)
        .addComponent(shieldAutoRun)
        .addComponent(TitledSeparator("Patrulha de plantão"))
        .addComponent(patrolPolling)
        .addLabeledComponent("Intervalo (minutos):", patrolInterval)
        .addComponent(comment("Comentários novos, CI quebrada e pedidos de revisão nos seus PRs. Usa a conta do GitHub já logada, sem pedir login."))
        .addComponent(patrolAutoFetch)
        .addComponent(TitledSeparator("IA em segundo plano"))
        .addComponent(aiBackground)
        .addLabeledComponent("Esperar as alterações pararem por (minutos):", aiIdle)
        .addLabeledComponent("Intervalo mínimo entre análises (minutos):", aiCooldown)
        .addLabeledComponent("Análises automáticas por dia, no máximo:", aiBudget)
        .addComponent(aiTriage)
        .addComponent(TitledSeparator("Inteligência"))
        .addComponent(feedback)
        .addComponent(comment("Fica em iron-dome/defesa/aprendizado-revisao.md: escreva ali também regras do time, uma por linha."))
        .addComponent(dossier)
        .addComponent(comment("Decisões por classe em iron-dome/defesa/decisoes.md, numa seção ## Arquivo.kt com itens - ."))
        .addComponent(conflicts)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    private fun pairs() = listOf(
        commitGuard to DefenseSettings::commitGuard,
        passiveRadar to DefenseSettings::passiveRadar,
        shieldSuggest to DefenseSettings::shieldSuggest,
        shieldAutoRun to DefenseSettings::shieldAutoRun,
        patrolPolling to DefenseSettings::patrolPolling,
        patrolAutoFetch to DefenseSettings::patrolAutoFetch,
        aiBackground to DefenseSettings::aiBackgroundImpact,
        aiTriage to DefenseSettings::aiTriage,
        feedback to DefenseSettings::feedbackLearning,
        dossier to DefenseSettings::dossier,
        conflicts to DefenseSettings::conflictDetection,
    )

    private fun numbers() = listOf(
        patrolInterval to DefenseSettings::patrolIntervalMinutes,
        aiIdle to DefenseSettings::aiIdleMinutes,
        aiCooldown to DefenseSettings::aiCooldownMinutes,
        aiBudget to DefenseSettings::aiDailyBudget,
    )

    override fun isModified() =
        pairs().any { (box, property) -> box.isSelected != property.get() } ||
            numbers().any { (spinner, property) -> spinner.number != property.get() }

    override fun apply() {
        pairs().forEach { (box, property) -> if (box.isSelected != property.get()) property.set(box.isSelected) }
        numbers().forEach { (spinner, property) -> if (spinner.number != property.get()) property.set(spinner.number) }
    }

    override fun reset() {
        pairs().forEach { (box, property) -> box.isSelected = property.get() }
        numbers().forEach { (spinner, property) -> spinner.number = property.get() }
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
}
