package com.mio.review.impacto

import com.intellij.openapi.vfs.VirtualFile

enum class ClassKind(val label: String) {
    CLASS("classe"),
    ABSTRACT("abstrata"),
    INTERFACE("interface"),
    ENUM("enum"),
    ANNOTATION("anotação"),
}

enum class EdgeKind { INHERITANCE, USAGE }

enum class Direction(val label: String) {
    BOTH("Ambas as direções"),
    EFFERENT("Só eferentes (de quem ela depende)"),
    AFFERENT("Só aferentes (quem depende dela)"),
}

/** Quanto do projeto pode quebrar se a classe mudar, medido pelas classes que dependem dela. */
enum class ChangeRisk(val label: String) {
    LOW("Baixo"),
    MEDIUM("Médio"),
    HIGH("Alto");

    override fun toString() = label

    companion object {
        const val MEDIUM_FROM = 3
        const val HIGH_FROM = 8

        /** Estar num ciclo sobe o risco um nível. */
        fun of(afferent: Int, inCycle: Boolean): ChangeRisk {
            val base = when {
                afferent >= HIGH_FROM -> HIGH
                afferent >= MEDIUM_FROM -> MEDIUM
                else -> LOW
            }
            return if (inCycle) entries[minOf(base.ordinal + 1, HIGH.ordinal)] else base
        }
    }
}

enum class TestCoverage(val label: String) {
    NONE("Sem testes"),
    PARTIAL("Parcial"),
    GOOD("Bem coberta"),
    /** A própria classe é um teste, então a métrica não se aplica. */
    TEST_CLASS("Classe de teste"),
}

/**
 * Classes de teste que citam a classe diretamente. Não é cobertura de linhas: os testes não são
 * executados. [dedicated] indica um teste com o nome da classe, como `ServiceTest`.
 */
class TestUsage(val testClasses: List<String>, val dedicated: Boolean, val isTest: Boolean = false) : Comparable<TestUsage> {
    val coverage: TestCoverage
        get() = when {
            isTest -> TestCoverage.TEST_CLASS
            testClasses.isEmpty() -> TestCoverage.NONE
            dedicated || testClasses.size >= WELL_COVERED_FROM -> TestCoverage.GOOD
            else -> TestCoverage.PARTIAL
        }

    override fun compareTo(other: TestUsage) = compareValuesBy(this, other, { it.coverage }, { it.testClasses.size })

    override fun toString() = if (testClasses.isEmpty()) coverage.label else "${coverage.label} (${testClasses.size})"

    companion object {
        const val WELL_COVERED_FROM = 3
        val NONE = TestUsage(emptyList(), false)
        val TEST_CLASS = TestUsage(emptyList(), false, isTest = true)

        fun dedicatedNames(className: String) =
            setOf("${className}Test", "${className}Tests", "${className}IT", "${className}Spec", "Test$className")
    }
}

/**
 * [afferent]: classes de produção que dependem desta. [efferent]: classes de produção de que esta
 * depende. Contam o projeto inteiro, não só as classes do diagrama.
 */
class ClassNode(
    val qualifiedName: String,
    val name: String,
    val packageName: String,
    val kind: ClassKind,
    val file: VirtualFile?,
    val filePath: String,
    /** Distância até a classe analisada: 0 é ela própria. */
    val level: Int,
    val afferent: Int,
    val efferent: Int,
    val tests: TestUsage,
) {
    var inCycle = false

    val risk: ChangeRisk get() = ChangeRisk.of(afferent, inCycle)

    /** Risco médio ou alto sem nenhum teste: merece cuidado antes de mudar. */
    val needsAttention: Boolean get() = risk != ChangeRisk.LOW && tests.coverage == TestCoverage.NONE
}

class Edge(val from: String, val to: String, val kind: EdgeKind) {
    var inCycle = false
}

class DependencyGraph(
    val focus: String,
    val direction: Direction,
    val depth: Int,
    val nodes: List<ClassNode>,
    val edges: List<Edge>,
    /** O limite de classes foi atingido e parte dos vizinhos ficou de fora. */
    val truncated: Boolean,
) {
    val cycleCount: Int get() = cycles.size

    /** Componentes fortemente conexos com mais de uma classe. */
    val cycles: List<Set<String>> = stronglyConnected().filter { it.size > 1 }

    init {
        val cycleOf = cycles.flatMapIndexed { index, cycle -> cycle.map { it to index } }.toMap()
        nodes.forEach { it.inCycle = it.qualifiedName in cycleOf }
        edges.forEach { edge ->
            val cycle = cycleOf[edge.from]
            edge.inCycle = cycle != null && cycle == cycleOf[edge.to]
        }
    }

    /** Tarjan, iterativo para não estourar a pilha em grafos grandes. */
    private fun stronglyConnected(): List<Set<String>> {
        val successors = edges.groupBy({ it.from }, { it.to })
        val index = mutableMapOf<String, Int>()
        val lowLink = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val result = mutableListOf<Set<String>>()
        var counter = 0

        for (start in nodes.map { it.qualifiedName }) {
            if (start in index) continue
            val work = ArrayDeque<Pair<String, Iterator<String>>>()
            index[start] = counter; lowLink[start] = counter; counter++
            stack.addLast(start); onStack += start
            work.addLast(start to successors[start].orEmpty().iterator())
            while (work.isNotEmpty()) {
                val (node, children) = work.last()
                if (children.hasNext()) {
                    val child = children.next()
                    if (child !in index) {
                        index[child] = counter; lowLink[child] = counter; counter++
                        stack.addLast(child); onStack += child
                        work.addLast(child to successors[child].orEmpty().iterator())
                    } else if (child in onStack) {
                        lowLink[node] = minOf(lowLink.getValue(node), index.getValue(child))
                    }
                    continue
                }
                work.removeLast()
                work.lastOrNull()?.let { (parent, _) ->
                    lowLink[parent] = minOf(lowLink.getValue(parent), lowLink.getValue(node))
                }
                if (lowLink[node] == index[node]) {
                    val component = mutableSetOf<String>()
                    do {
                        val member = stack.removeLast()
                        onStack -= member
                        component += member
                    } while (member != node)
                    result += component
                }
            }
        }
        return result
    }
}
