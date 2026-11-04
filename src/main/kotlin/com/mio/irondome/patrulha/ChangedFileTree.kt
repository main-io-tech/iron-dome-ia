package com.mio.irondome.patrulha

/**
 * Uma pasta dos arquivos alterados do PR. [name] pode juntar várias pastas de um filho só, como
 * `src/main/kotlin`; [path] é o caminho completo a partir da raiz do repositório.
 */
class FileFolder(val path: String, val name: String, val folders: List<FileFolder>, val files: List<ChangedFile>) {
    /** Todos os arquivos abaixo, na ordem em que aparecem na árvore. */
    val allFiles: List<ChangedFile> by lazy { folders.flatMap { it.allFiles } + files }
    val additions: Int get() = allFiles.sumOf { it.additions }
    val deletions: Int get() = allFiles.sumOf { it.deletions }
}

object ChangedFileTree {

    private class Node(val name: String) {
        val folders = sortedMapOf<String, Node>(String.CASE_INSENSITIVE_ORDER)
        val files = mutableListOf<ChangedFile>()
    }

    /** A raiz tem [FileFolder.path] vazio; pastas antes dos arquivos, cada grupo em ordem alfabética. */
    fun build(files: List<ChangedFile>): FileFolder {
        val root = Node("")
        files.forEach { file ->
            val parts = file.path.split('/')
            val node = parts.dropLast(1).fold(root) { parent, part -> parent.folders.getOrPut(part) { Node(part) } }
            node.files += file
        }
        return convert(root, "", "", compact = false)
    }

    private fun convert(node: Node, parentPath: String, prefix: String, compact: Boolean): FileFolder {
        val name = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
        val path = listOf(parentPath, node.name).filter { it.isNotEmpty() }.joinToString("/")
        if (compact && node.files.isEmpty() && node.folders.size == 1) {
            return convert(node.folders.values.single(), path, name, compact = true)
        }
        return FileFolder(
            path = path,
            name = name,
            folders = node.folders.values.map { convert(it, path, "", compact = true) },
            files = node.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path.substringAfterLast('/') }),
        )
    }
}
