package com.mio.irondome.interceptor

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * O terminal da IDE, para a IA rodar à vista do usuário. Vem do plugin de terminal, que é opcional: sem ele,
 * não há extensão e a IA roda na aba.
 */
interface AgentTerminal {

    /**
     * O shell que o terminal do projeto abre, como `powershell.exe` ou `/bin/zsh`. Fora da EDT: o plugin de
     * terminal resolve o shell bloqueando a thread.
     */
    fun shellPath(project: Project): String?

    /** Na EDT. Abre uma aba nova do terminal em [workDir], chamada [title], e executa [command] nela. */
    fun run(project: Project, workDir: String, title: String, command: String)

    companion object {
        val EP = ExtensionPointName<AgentTerminal>("com.mio.irondome.agentTerminal")

        fun available(): AgentTerminal? = EP.extensionList.firstOrNull()
    }
}
