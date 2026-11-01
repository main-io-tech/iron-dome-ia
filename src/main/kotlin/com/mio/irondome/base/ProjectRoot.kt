package com.mio.irondome.base

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Pasta de onde saem os caminhos dos comentários e do `iron-dome/`. Prefere o `basePath` ao
 * [guessProjectDir], que num projeto com o `.iml` dentro da `.idea` e outros módulos devolve a própria `.idea`.
 */
fun Project.rootDir(): VirtualFile? =
    basePath?.let(LocalFileSystem.getInstance()::findFileByPath)?.takeIf { it.isDirectory } ?: guessProjectDir()
