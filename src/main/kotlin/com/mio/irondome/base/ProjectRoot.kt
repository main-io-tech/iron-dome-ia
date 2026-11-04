package com.mio.irondome.base

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import com.mio.irondome.radar.ReviewCommentService

/**
 * Pasta de onde saem os caminhos dos comentários e do `iron-dome/`: o projeto escolhido no Iron Dome quando a
 * janela tem mais de um, senão o `basePath`. Prefere o `basePath` ao [guessProjectDir], que num projeto com o
 * `.iml` dentro da `.idea` e outros módulos devolve a própria `.idea`.
 */
fun Project.rootDir(): VirtualFile? =
    service<IronDomeRoot>().selectedDir()
        ?: basePath?.let(LocalFileSystem.getInstance()::findFileByPath)?.takeIf { it.isDirectory }
        ?: guessProjectDir()

/** O caminho de [rootDir], com `/`. */
fun Project.rootPath(): String? = rootDir()?.path

/** Um projeto da janela que o Iron Dome pode vigiar: a pasta de um módulo que não está dentro de outro. */
class RootCandidate(val path: String, val name: String)

/**
 * Com mais de um projeto na janela (módulos em pastas separadas, como os anexados com Attach), qual deles o
 * Iron Dome vigia. Cada projeto guarda os seus comentários e a sua pasta `iron-dome/`.
 */
@Service(Service.Level.PROJECT)
@State(name = "IronDomeRoot", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IronDomeRoot(private val project: Project) : PersistentStateComponent<IronDomeRoot.RootState> {

    class RootState {
        /** Vazio: o `basePath` do projeto. */
        var path: String = ""
    }

    fun interface Listener {
        fun rootChanged()

        companion object {
            @Topic.ProjectLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    private var state = RootState()

    override fun getState(): RootState = state

    override fun loadState(loaded: RootState) {
        state = loaded
    }

    fun selectedDir(): VirtualFile? =
        state.path.takeIf { it.isNotEmpty() }?.let(LocalFileSystem.getInstance()::findFileByPath)?.takeIf { it.isDirectory }

    /** As pastas dos módulos que não estão dentro de outra, pelo nome. Um projeto só de um módulo tem uma. */
    fun candidates(): List<RootCandidate> {
        val roots = ModuleManager.getInstance(project).modules
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.asList() }
            .filter { it.isInLocalFileSystem && it.isDirectory }
            .distinct()
        return topLevel(roots.map { it.path }).map { RootCandidate(it, it.substringAfterLast('/')) }.sortedBy { it.name.lowercase() }
    }

    /**
     * Passa o Iron Dome para o projeto em [path]. Os comentários e a revisão aberta do atual ficam guardados e
     * voltam quando ele for escolhido de novo. Na EDT; devolve o motivo, se não deu para trocar.
     */
    fun select(path: String): String? {
        val from = project.rootPath() ?: return "O projeto não tem pasta no disco."
        if (path == from) return null
        val radar = project.service<ReviewCommentService>()
        if (radar.activePullRequest != 0) {
            return "Encerre a revisão do PR #${radar.activePullRequest} na Patrulha antes de trocar de projeto."
        }
        radar.switchRoot(from, path) { state.path = path }
        project.messageBus.syncPublisher(Listener.TOPIC).rootChanged()
        return null
    }

    companion object {
        fun getInstance(project: Project): IronDomeRoot = project.service()

        /** Tira as pastas que estão dentro de outra da lista: num projeto Gradle com submódulos sobra só a raiz. */
        fun topLevel(paths: List<String>): List<String> {
            val normalized = paths.map { it.replace('\\', '/').trimEnd('/') }.distinct()
            return normalized.filter { path -> normalized.none { other -> other != path && path.startsWith("$other/") } }
        }
    }
}
