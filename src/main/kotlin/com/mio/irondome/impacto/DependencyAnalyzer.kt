package com.mio.irondome.impacto

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import java.util.concurrent.Callable

/**
 * Dependências entre classes Java e Kotlin do projeto, via UAST. Uma classe depende de outra quando
 * cita o tipo dela, herda dela, chama o construtor ou usa membros estáticos, de `object` ou de nível de
 * arquivo. Classes aninhadas contam como a classe de topo que as contém. Bibliotecas ficam de fora.
 * O diagrama e as contagens usam só o código de produção; os testes entram à parte, em [ClassNode.tests].
 *
 * Cada passo roda numa read action própria, para não segurar a IDE durante a análise inteira.
 */
class DependencyAnalyzer(
    private val project: Project,
    private val indicator: ProgressIndicator,
) {
    private val scope: GlobalSearchScope = GlobalSearchScopesCore.projectProductionScope(project)
    private val testScope: GlobalSearchScope = GlobalSearchScopesCore.projectTestScope(project)
    private val efferent = mutableMapOf<String, Set<String>>()
    private val supers = mutableMapOf<String, Set<String>>()
    private val afferent = mutableMapOf<String, Set<String>>()

    fun analyze(focus: String, depth: Int, direction: Direction): DependencyGraph {
        val levels = linkedMapOf(focus to 0)
        var frontier = listOf(focus)
        var truncated = false
        for (level in 1..depth) {
            val next = mutableListOf<String>()
            for (name in frontier) {
                step("Buscando vizinhos de ${simpleName(name)}", levels.size)
                val neighbors = when (direction) {
                    Direction.EFFERENT -> efferentOf(name)
                    Direction.AFFERENT -> afferentOf(name)
                    Direction.BOTH -> efferentOf(name) + afferentOf(name)
                }
                for (neighbor in neighbors.sorted()) {
                    if (neighbor in levels) continue
                    if (levels.size >= MAX_NODES) {
                        truncated = true
                        break
                    }
                    levels[neighbor] = level
                    next += neighbor
                }
            }
            frontier = next
        }

        val names = levels.keys
        names.forEachIndexed { index, name ->
            step("Medindo ${simpleName(name)}", index, names.size)
            efferentOf(name)
            afferentOf(name)
        }
        val nodes = names.mapIndexedNotNull { index, name ->
            step("Procurando testes de ${simpleName(name)}", index, names.size)
            describe(name, levels.getValue(name))
        }
        val edges = edgesBetween(nodes.map { it.qualifiedName }.toSet())
        return DependencyGraph(focus, direction, depth, nodes, edges, truncated)
    }

    private fun edgesBetween(names: Set<String>): List<Edge> {
        val pairs = linkedSetOf<Pair<String, String>>()
        for (from in names) efferent[from].orEmpty().filter { it in names }.forEach { pairs += from to it }
        for (to in names) afferent[to].orEmpty().filter { it in names }.forEach { pairs += it to to }
        return pairs.map { (from, to) ->
            Edge(from, to, if (to in supers[from].orEmpty()) EdgeKind.INHERITANCE else EdgeKind.USAGE)
        }
    }

    private fun describe(name: String, level: Int): ClassNode? {
        val tests = testsOf(name)
        return read {
            val psiClass = findClass(name) ?: return@read null
            ClassNode(
                qualifiedName = name,
                name = psiClass.name ?: simpleName(name),
                packageName = name.substringBeforeLast('.', ""),
                kind = kindOf(psiClass),
                file = psiClass.navigationElement.containingFile?.virtualFile,
                filePath = relativePath(psiClass).orEmpty(),
                level = level,
                afferent = afferent[name].orEmpty().size,
                efferent = efferent[name].orEmpty().size,
                tests = tests,
            )
        }
    }

    private fun testsOf(name: String): TestUsage = read {
        val psiClass = findClass(name) ?: return@read null
        val file = psiClass.navigationElement.containingFile?.virtualFile
        if (file != null && testScope.contains(file)) return@read TestUsage.TEST_CLASS
        val uClass = psiClass.toUElement() as? UClass ?: return@read null
        val found = sortedSetOf<String>()
        for (target in rootsOf(uClass, psiClass)) {
            ReferencesSearch.search(target, testScope).forEach(Processor { reference ->
                ProgressManager.checkCanceled()
                topLevelClass(reference.element)?.qualifiedName?.takeIf { it != name }?.let(found::add)
                true
            })
        }
        val dedicated = TestUsage.dedicatedNames(psiClass.name ?: simpleName(name))
        TestUsage(found.toList(), found.any { simpleName(it) in dedicated })
    } ?: TestUsage.NONE

    private fun efferentOf(name: String): Set<String> = efferent.getOrPut(name) {
        read {
            val psiClass = findClass(name) ?: return@read emptySet()
            val uClass = psiClass.toUElement() as? UClass ?: return@read emptySet()
            supers[name] = psiClass.supers.mapNotNull { topLevelName(it) }.toSet()
            val result = mutableSetOf<String>()
            for (root in rootsOf(uClass, psiClass)) {
                for (element in SyntaxTraverser.psiTraverser(root)) {
                    ProgressManager.checkCanceled()
                    for (reference in element.references) {
                        val target = reference.resolve() ?: continue
                        val owner = dependencyOwner(target) ?: continue
                        if (owner != name) result += owner
                    }
                }
            }
            result
        }.orEmpty()
    }

    private fun afferentOf(name: String): Set<String> = afferent.getOrPut(name) {
        read {
            val psiClass = findClass(name) ?: return@read emptySet()
            val uClass = psiClass.toUElement() as? UClass ?: return@read emptySet()
            val result = mutableSetOf<String>()
            for (target in rootsOf(uClass, psiClass)) {
                ReferencesSearch.search(target, scope).forEach(Processor { reference ->
                    ProgressManager.checkCanceled()
                    topLevelName(reference.element)?.takeIf { it != name }?.let(result::add)
                    true
                })
            }
            result
        }.orEmpty()
    }

    /** Fachadas de arquivo do Kotlin (funções de topo) não têm corpo de classe: valem os membros. */
    private fun rootsOf(uClass: UClass, psiClass: PsiClass): List<PsiElement> {
        val source = uClass.sourcePsi ?: return listOf(psiClass)
        if (source !is PsiFile) return listOf(source)
        return (uClass.methods.toList() + uClass.fields.toList()).mapNotNull { it.sourcePsi }
    }

    /** Classe de topo que conta como dependência quando [target] é referenciado, ou nulo se não contar. */
    private fun dependencyOwner(target: PsiElement): String? {
        val owner = topLevelClass(target)?.takeIf { inScope(it.javaPsi) } ?: return null
        val element = target.toUElement()
        val counts = element is UClass ||
            (element is UMethod && element.isConstructor) ||
            owner.sourcePsi is PsiFile ||
            (element as? PsiModifierListOwner)?.hasModifierProperty(PsiModifier.STATIC) == true
        return owner.qualifiedName.takeIf { counts }
    }

    private fun topLevelName(element: PsiElement): String? =
        topLevelClass(element)?.takeIf { inScope(it.javaPsi) }?.qualifiedName

    private fun topLevelClass(element: PsiElement): UClass? {
        var current = element.getUastParentOfType(UClass::class.java, false) ?: return null
        while (true) {
            current = current.uastParent?.getParentOfType(UClass::class.java, false) ?: break
        }
        return current.takeIf { it.qualifiedName != null }
    }

    private fun inScope(psiClass: PsiClass): Boolean {
        val file = psiClass.navigationElement.containingFile?.virtualFile ?: return false
        return scope.contains(file)
    }

    /** Procura também nos testes, para que uma classe de teste aberta no editor possa ser analisada. */
    private fun findClass(name: String): PsiClass? =
        JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.projectScope(project))

    private fun relativePath(psiClass: PsiClass): String? {
        val file = psiClass.navigationElement.containingFile?.virtualFile ?: return null
        val base = project.guessProjectDir() ?: return file.path
        return VfsUtilCore.getRelativePath(file, base) ?: file.path
    }

    private fun step(text: String, done: Int, total: Int = MAX_NODES) {
        indicator.checkCanceled()
        indicator.text = text
        indicator.fraction = (done.toDouble() / total.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    /** Recomeça se a IDE precisar escrever no meio, em vez de travá-la. */
    private fun <T> read(action: () -> T): T? =
        ReadAction.nonBlocking(Callable { action() }).inSmartMode(project).wrapProgress(indicator).executeSynchronously()

    companion object {
        const val MAX_NODES = 60

        fun kindOf(psiClass: PsiClass): ClassKind = when {
            psiClass.isAnnotationType -> ClassKind.ANNOTATION
            psiClass.isInterface -> ClassKind.INTERFACE
            psiClass.isEnum -> ClassKind.ENUM
            psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> ClassKind.ABSTRACT
            else -> ClassKind.CLASS
        }

        fun simpleName(qualifiedName: String): String = qualifiedName.substringAfterLast('.')

        /** Classe de topo em [offset] do arquivo, ou a primeira classe dele. Exige read action. */
        fun classAt(file: PsiFile, offset: Int?): String? {
            val element = offset?.let(file::findElementAt)
            var current = element?.getUastParentOfType(UClass::class.java, false)
            while (current != null) {
                current = current.uastParent?.getParentOfType(UClass::class.java, false) ?: break
            }
            return (current ?: (file.toUElement() as? UFile)?.classes?.firstOrNull())?.qualifiedName
        }
    }
}
