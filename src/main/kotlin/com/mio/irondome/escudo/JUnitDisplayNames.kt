package com.mio.irondome.escudo

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope

/**
 * O texto do `@DisplayName` e as `@Tag` escritos na classe ou no método de teste, a partir da URL que o
 * runner do JUnit dá a cada nó (`java:suite://loja.FreteTest`, `java:test://loja.FreteTest/calcula`). Usado
 * só pela EDT; o cache vale para uma execução.
 */
class JUnitDisplayNames(private val project: Project) {

    private val cache = mutableMapOf<String, String?>()
    private val tagCache = mutableMapOf<String, List<String>>()

    fun clear() {
        cache.clear()
        tagCache.clear()
    }

    fun of(locationUrl: String): String? {
        if (locationUrl in cache) return cache[locationUrl]
        val name = read { resolve(project, locationUrl)?.let(::displayNameOf) } ?: return null
        cache[locationUrl] = name.value
        return name.value
    }

    /** As tags do teste: as do método ou, sem elas, as da classe (e das classes de fora, nas `@Nested`). */
    fun tagsOf(locationUrl: String): List<String> {
        tagCache[locationUrl]?.let { return it }
        val tags = read { resolve(project, locationUrl)?.let(::effectiveTags) }?.value ?: return emptyList()
        tagCache[locationUrl] = tags
        return tags
    }

    /** Nulo enquanto o projeto indexa, para tentar de novo no próximo refresh; o valor pode ser nulo. */
    private fun <T> read(compute: () -> T): Holder<T>? {
        if (DumbService.isDumb(project)) return null
        return try {
            Holder(ReadAction.compute<T, RuntimeException> { compute() })
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    private class Holder<T>(val value: T)

    companion object {
        private const val DISPLAY_NAME = "org.junit.jupiter.api.DisplayName"
        private const val TAG = "org.junit.jupiter.api.Tag"
        private const val TAGS = "org.junit.jupiter.api.Tags"

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

        /** As do próprio elemento; sem nenhuma, as da classe que o contém, subindo até achar. Exige read action. */
        fun effectiveTags(owner: PsiModifierListOwner): List<String> {
            var current: PsiModifierListOwner? = owner
            while (current != null) {
                tagsOn(current).takeIf { it.isNotEmpty() }?.let { return it }
                current = (current as? PsiMember)?.containingClass
            }
            return emptyList()
        }

        /** `@Tag`, `@Tags` e anotações próprias marcadas com `@Tag`, como um `@Lento`. Exige read action. */
        fun tagsOn(owner: PsiModifierListOwner): List<String> =
            owner.modifierList?.annotations.orEmpty().flatMap { annotation ->
                when (annotation.qualifiedName) {
                    TAG -> listOfNotNull(tagValue(annotation))
                    TAGS -> (annotation.findAttributeValue("value") as? PsiArrayInitializerMemberValue)
                        ?.initializers?.filterIsInstance<PsiAnnotation>()?.mapNotNull(::tagValue).orEmpty()
                    else -> (annotation.nameReferenceElement?.resolve() as? PsiClass)
                        ?.takeIf { it.isAnnotationType }
                        ?.let { meta -> AnnotationUtil.findAnnotation(meta, TAG)?.let(::tagValue) }
                        ?.let(::listOf).orEmpty()
                }
            }.distinct()

        private fun tagValue(annotation: PsiAnnotation): String? =
            AnnotationUtil.getStringAttributeValue(annotation, "value")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
