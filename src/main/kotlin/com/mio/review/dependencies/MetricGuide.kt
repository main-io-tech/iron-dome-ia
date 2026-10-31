package com.mio.review.dependencies

/** Explicações das métricas e recomendações por classe, mostradas na tabela e no diagrama. */
object MetricGuide {

    class Topic(val id: String, val title: String, val text: String)

    val afferent = Topic(
        "afferent",
        "Aferentes: quem depende dela",
        "Quantas classes do projeto usam esta classe. Se você mudar algo que elas usam, como o nome de " +
            "um método, um parâmetro ou o resultado de uma conta, todas essas classes podem quebrar ou " +
            "passar a se comportar diferente. Quanto maior o número, mais cuidado a alteração pede.",
    )

    val efferent = Topic(
        "efferent",
        "Eferentes: de quem ela depende",
        "Quantas classes do projeto esta classe usa: tipos que ela declara, classes que ela cria, herda " +
            "ou implementa e métodos estáticos que ela chama. Se alguma dessas classes mudar, esta pode " +
            "precisar mudar junto. Um número alto costuma indicar uma classe que faz coisas demais.",
    )

    val risk = Topic(
        "risk",
        "Risco ao alterar",
        "Estima quanto do projeto uma mudança nesta classe pode atingir, olhando quantas classes dependem " +
            "dela. Baixo: menos de ${ChangeRisk.MEDIUM_FROM} classes dependem dela. Médio: de " +
            "${ChangeRisk.MEDIUM_FROM} a ${ChangeRisk.HIGH_FROM - 1}. Alto: ${ChangeRisk.HIGH_FROM} ou mais. " +
            "Se ela faz parte de um ciclo de dependência, o risco sobe um nível.",
    )

    val tests = Topic(
        "tests",
        "Testes",
        "Mostra se existem testes que usam esta classe diretamente, que são a sua rede de segurança: se " +
            "a alteração quebrar algo, um teste falha antes de o problema chegar ao usuário. Bem coberta: " +
            "existe um teste com o nome dela (por exemplo, ServiceTest) ou ${TestUsage.WELL_COVERED_FROM} " +
            "ou mais classes de teste a usam. Parcial: uma ou duas classes de teste a usam. Sem testes: " +
            "nenhum teste a usa diretamente. Classe de teste: a própria classe é um teste, então a métrica " +
            "não se aplica. O plugin não executa os testes nem mede quais linhas eles " +
            "percorrem; para isso, use Run with Coverage.",
    )

    val cycle = Topic(
        "cycle",
        "Ciclo de dependência",
        "Acontece quando uma classe depende de outra que, direta ou indiretamente, depende dela de volta " +
            "(A usa B e B usa A). Classes em ciclo acabam mudando juntas e são mais difíceis de testar " +
            "separadas. No diagrama, as setas do ciclo ficam vermelhas.",
    )

    val changes = Topic(
        "changes",
        "Alterações não commitadas",
        "Classes cujo arquivo foi mexido e ainda não entrou num commit do git, as mesmas da aba Alterações: " +
            "modificadas, movidas, novas ou ainda fora do git. No diagrama elas ganham um contorno azul. São " +
            "essas mudanças que vão para o próximo commit, então junte com o risco: uma classe alterada com " +
            "risco médio ou alto merece uma revisão cuidadosa do diff e os testes rodando antes do commit.",
    )

    val reading = Topic(
        "diagram",
        "Como ler o diagrama",
        "A classe analisada fica no centro, e cada anel em volta está um passo de dependência mais longe " +
            "dela. Cada caixa é uma classe e a seta sai de quem usa para quem é usado. Seta tracejada indica herança " +
            "ou implementação de interface. A cor mostra o risco ao alterar: verde é baixo, amarelo é médio " +
            "e vermelho é alto. O sinal ⚠ marca classes com risco médio ou alto e sem testes, e o contorno " +
            "azul marca classes com alterações ainda não commitadas. Clique numa classe para ver os detalhes " +
            "e dê dois cliques para abrir o código.",
    )

    val topics = listOf(reading, afferent, efferent, risk, tests, changes, cycle)

    fun riskReason(node: ClassNode): String {
        val dependents = when (node.afferent) {
            0 -> "Nenhuma classe do projeto depende dela"
            1 -> "1 classe do projeto depende dela"
            else -> "${node.afferent} classes do projeto dependem dela"
        }
        return if (node.inCycle) "$dependents, e ela faz parte de um ciclo, o que sobe o risco um nível." else "$dependents."
    }

    fun advice(node: ClassNode): String {
        val impact = when (node.risk) {
            ChangeRisk.LOW -> "Uma alteração aqui tem efeito limitado."
            ChangeRisk.MEDIUM -> "Uma alteração aqui pode quebrar as classes que a usam."
            ChangeRisk.HIGH -> "Uma alteração aqui pode se espalhar por boa parte do projeto."
        }
        val protection = when (node.tests.coverage) {
            TestCoverage.NONE ->
                if (node.risk == ChangeRisk.LOW) "Nenhum teste a usa; criar um teste simples já ajuda a evitar surpresas."
                else "Nenhum teste a usa: antes de alterar, escreva testes que garantam o comportamento atual."
            TestCoverage.PARTIAL -> "Poucos testes a usam: confira se eles passam pela parte que você vai mudar e complete se faltar."
            TestCoverage.GOOD -> "Ela tem testes: rode-os depois da alteração, junto com os testes de quem depende dela."
            TestCoverage.TEST_CLASS -> "Ela é um teste: depois de alterá-la, rode-a e confira se ainda verifica o que deveria."
        }
        return "$impact $protection"
    }

    /** Mostrada só quando a classe tem alterações não commitadas. */
    fun changeAdvice(node: ClassNode): String {
        if (node.risk == ChangeRisk.LOW) return "Ela tem alterações que ainda não foram para um commit. Confira o diff antes de commitar."
        val tests = if (node.tests.coverage == TestCoverage.NONE) " Como nenhum teste a usa, vale escrever um antes do commit." else ""
        return "Ela tem alterações que ainda não foram para um commit e o risco de alterá-la é " +
            "${node.risk.label.lowercase()}. Revise o diff com calma e rode os testes antes de commitar.$tests"
    }
}
