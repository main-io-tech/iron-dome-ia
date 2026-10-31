package com.mio.irondome.escudo

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope

/**
 * O texto do `@DisplayName` escrito na classe ou no método de teste, a partir da URL que o runner do
 * JUnit dá a cada nó (`java:suite://loja.FreteTest`, `java:test://loja.FreteTest/calcula`). Usado só pela
 * EDT; o cache vale para uma execução.
 */
class JUnitDisplayNames(private val project: Project) {

    private val cache = mutableMapOf<String, String?>()

    fun clear() = cache.clear()

    fun of(locationUrl: String): String? {
        if (locationUrl in cache) return cache[locationUrl]
        if (DumbService.isDumb(project)) return null
        val name = try {
            ReadAction.compute<String?, RuntimeException> { resolve(project, locationUrl)?.let(::displayNameOf) }
        } catch (_: IndexNotReadyException) {
            return null
        }
        cache[locationUrl] = name
        return name
    }

    companion object {
        private const val DISPLAY_NAME = "org.junit.jupiter.api.DisplayName"

        /** A classe ou o método da URL, com `$` separando as classes aninhadas. Exige read action. */
        fun resolve(project: Project, locationUrl: String): PsiModifierListOwner? {
            if (!locationUrl.startsWith("java:")) return null
            val path = locationUrl.substringAfter("://", "").ifEmpty { return null }
            val className = path.substringBefore('/').replace('$', '.')
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
                ?: return null
            val method = path.substringAfter('/', "").substringBefore('(')
            return if (method.isEmpty()) psiClass else psiClass.findMethodsByName(method, false).firstOrNull()
        }

        fun displayNameOf(owner: PsiModifierListOwner): String? =
            AnnotationUtil.findAnnotation(owner, DISPLAY_NAME)
                ?.let { AnnotationUtil.getStringAttributeValue(it, "value") }
                ?.takeIf { it.isNotBlank() }
    }
}
