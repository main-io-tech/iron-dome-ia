package com.mio.irondome.defesa

import com.mio.irondome.base.Tone
import com.mio.irondome.impacto.ChangeRisk
import com.mio.irondome.impacto.TestCoverage

/**
 * O risco de alterar uma classe, com memória: a estrutura (quem depende dela, ciclos, testes) somada ao que o
 * git conta (quantas vezes mudou, quantas foram correções, quem mexeu). Cada ponto vem com o motivo, porque
 * alerta sem explicação vira ruído desligado em uma semana.
 */
data class RiskScore(val points: Int, val reasons: List<String>) {

    val level: ChangeRisk
        get() = when {
            points >= HIGH_FROM -> ChangeRisk.HIGH
            points >= MEDIUM_FROM -> ChangeRisk.MEDIUM
            else -> ChangeRisk.LOW
        }

    val tone: Tone
        get() = when (level) {
            ChangeRisk.HIGH -> Tone.RED
            ChangeRisk.MEDIUM -> Tone.YELLOW
            ChangeRisk.LOW -> Tone.GREEN
        }

    /** "Alto: 14 classes dependem dela, mudou 9 vezes em 6 meses, 4 delas correções". */
    fun explain(): String = if (reasons.isEmpty()) level.label else "${level.label}: ${reasons.joinToString(", ")}"

    companion object {
        const val MEDIUM_FROM = 30
        const val HIGH_FROM = 60

        /**
         * [afferent] classes de produção que dependem da classe; [coverage] os testes que a citam; [history] o que o
         * git conta do arquivo dela, ou nulo sem git.
         */
        fun of(afferent: Int, inCycle: Boolean, coverage: TestCoverage, history: FileHistory?): RiskScore {
            var points = 0
            val reasons = mutableListOf<String>()
            when {
                afferent >= ChangeRisk.HIGH_FROM -> {
                    points += 45
                    reasons += "$afferent classes dependem dela"
                }
                afferent >= ChangeRisk.MEDIUM_FROM -> {
                    points += 25
                    reasons += "$afferent classes dependem dela"
                }
                afferent > 0 -> points += 5 * afferent
            }
            if (inCycle) {
                points += 15
                reasons += "está num ciclo de dependências"
            }
            when (coverage) {
                TestCoverage.NONE -> {
                    points += 20
                    reasons += "nenhum teste a cita"
                }
                TestCoverage.PARTIAL -> points += 5
                TestCoverage.GOOD, TestCoverage.TEST_CLASS -> Unit
            }
            if (history != null && history.commits > 0) {
                if (history.commits >= CHURN_HIGH) {
                    points += 15
                    reasons += "mudou ${history.commits} vezes em ${ChangeHistory.MONTHS} meses"
                } else if (history.commits >= CHURN_MEDIUM) {
                    points += 8
                    reasons += "mudou ${history.commits} vezes em ${ChangeHistory.MONTHS} meses"
                }
                if (history.fixes >= FIXES_HIGH) {
                    points += 20
                    reasons += "${history.fixes} dessas foram correções"
                } else if (history.fixes > 0) {
                    points += 8 * history.fixes
                    reasons += if (history.fixes == 1) "1 delas foi correção" else "${history.fixes} delas foram correções"
                }
                if (history.singleAuthor) {
                    points += 5
                    reasons += "só ${history.authors.first()} mexeu nela"
                }
            }
            return RiskScore(points.coerceIn(0, 100), reasons)
        }

        private const val CHURN_MEDIUM = 4
        private const val CHURN_HIGH = 8
        private const val FIXES_HIGH = 3
    }
}
