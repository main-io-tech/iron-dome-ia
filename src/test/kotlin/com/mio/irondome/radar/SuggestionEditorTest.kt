package com.mio.irondome.radar

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SuggestionEditorTest : BasePlatformTestCase() {

    private val pedido = """
        class Pedido {
            int total() {
                return 0;
            }
        }
    """.trimIndent()

    fun testTheSuggestionIsWrittenInACopyThatResolvesTheProject() {
        myFixture.addFileToProject("Frete.java", "class Frete { int peso() { return 1; } }")
        val file = myFixture.addFileToProject("Pedido.java", pedido).virtualFile

        val suggestion = SuggestionEditor.forLines(project, file, 2..2, "        return new Frete().peso();")
        val copy = suggestion.field.document
        PsiDocumentManager.getInstance(project).commitDocument(copy)
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(copy)!!

        assertEquals("        return new Frete().peso();", suggestion.text)
        assertEquals("a cópia aponta para o arquivo de verdade", file, psi.originalFile.virtualFile)
        val frete = PsiTreeUtil.findChildrenOfType(psi, PsiJavaCodeReferenceElement::class.java).first { it.referenceName == "Frete" }
        assertNotNull("Frete, de outro arquivo do projeto, resolve na cópia", frete.resolve())
        assertEquals("o arquivo original não muda", pedido, myFixture.psiManager.findFile(file)!!.text)
    }

    fun testOnlyTheHighlightedLinesCanBeEdited() {
        val file = myFixture.addFileToProject("Pedido.java", pedido).virtualFile

        val suggestion = SuggestionEditor.forLines(project, file, 2..2, "")
        val copy = suggestion.field.document

        assertEquals("        return 0;", suggestion.text)
        assertNotNull("a linha da classe fica só de leitura", copy.getOffsetGuard(0))
        assertNotNull("o fim do arquivo também", copy.getOffsetGuard(copy.textLength - 1))
        val start = copy.text.indexOf("return 0;")
        WriteCommandAction.runWriteCommandAction(project) { copy.replaceString(start, start + "return 0;".length, "return 42;") }
        assertEquals("        return 42;", suggestion.text)
    }

    fun testAWholeFileExampleGoesAfterTheFileContents() {
        val file = myFixture.addFileToProject("Pedido.java", pedido).virtualFile

        val suggestion = SuggestionEditor.atEnd(project, file, "class Frete {}")

        assertEquals("class Frete {}", suggestion.text)
        assertTrue(suggestion.field.document.text.startsWith(pedido))
    }
}
