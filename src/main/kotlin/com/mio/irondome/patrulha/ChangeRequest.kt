package com.mio.irondome.patrulha

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mio.irondome.radar.CommentTarget
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class CodeStatus(val label: String) {
    NOT_CHECKED("Não verificado"),
    NO_NEW_COMMITS("Sem commits novos"),
    UNCHANGED("Sem alteração"),
    MOVED("Só mudou de lugar"),
    CHANGED("Alterado"),
    /** O head tem o código sugerido no pedido. */
    AS_SUGGESTED("Como sugerido"),
    FILE_REMOVED("Arquivo removido"),
    NOT_APPLICABLE("Não se aplica"),
}

enum class ThreadStatus(val label: String) {
    NONE("Sem conversa"),
    NOT_CHECKED("Não verificada"),
    OPEN("Aberta"),
    REPLIED("Respondida"),
    RESOLVED("Resolvida"),
}

enum class AiVerdict(val label: String) {
    PENDING("Aguardando"),
    DONE("Atendido"),
    NOT_DONE("Não atendido"),
}

/**
 * Pedido de alteração enviado a um PR. Linhas 1-based, como no GitHub; 0 fora de [CommentTarget.LINES].
 * [commit] é o head do PR no envio; [commentId] é o comentário no GitHub, nulo quando o pedido foi no
 * texto geral da revisão (pacotes e arquivos fora do PR).
 */
data class ChangeRequest(
    val id: String,
    val path: String,
    val target: CommentTarget,
    val startLine: Int,
    val endLine: Int,
    val snippet: String,
    val text: String,
    val commit: String,
    val commentId: Long?,
    val url: String?,
    val sentAt: String,
    val code: CodeStatus = CodeStatus.NOT_CHECKED,
    val codeCommit: String? = null,
    val thread: ThreadStatus = if (commentId == null) ThreadStatus.NONE else ThreadStatus.NOT_CHECKED,
    val lastReply: String? = null,
    val ai: AiVerdict = AiVerdict.PENDING,
    val aiNote: String = "",
    /** Código de exemplo enviado com o pedido; vazio quando não houve. */
    val suggestion: String = "",
) {
    val location: String
        get() = when (target) {
            CommentTarget.LINES -> if (startLine == endLine) "linha $startLine" else "linhas $startLine–$endLine"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.PACKAGE -> "pacote inteiro"
        }

    /** Feito por pelo menos um dos sinais: o código mudou, a conversa foi resolvida ou a IA confirmou. */
    val likelyDone: Boolean
        get() = code == CodeStatus.CHANGED || code == CodeStatus.AS_SUGGESTED || code == CodeStatus.FILE_REMOVED ||
            thread == ThreadStatus.RESOLVED || ai == AiVerdict.DONE

    /** Nenhum sinal de que foi feito, depois de pelo menos uma verificação com commits novos. */
    val likelyPending: Boolean
        get() = !likelyDone && (code == CodeStatus.UNCHANGED || code == CodeStatus.MOVED || ai == AiVerdict.NOT_DONE)
}

/** Os pedidos de um PR, gravados em `iron-dome/pull-requests/pr-<número>.md`. */
data class PullRequestRecord(
    val number: Int,
    val title: String,
    val url: String,
    val headRef: String,
    val baseRef: String,
    val requests: List<ChangeRequest>,
    val checkedAt: String? = null,
) {
    companion object {
        const val DIR = "pull-requests"

        fun fileName(number: Int) = "pr-$number.md"

        fun numberOf(fileName: String): Int? = Regex("""pr-(\d+)\.md""").matchEntire(fileName)?.groupValues?.get(1)?.toInt()
    }
}

object ChangeRequestMarkdown {

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private const val RECORD_TAG = "iron-dome-pr"
    private const val REQUEST_TAG = "iron-dome-pedido"
    private const val AI_LABEL = "**Atendido (IA)**"
    private const val NOTE_LABEL = "**Parecer da IA:**"
    private const val NOTE_PLACEHOLDER = "_ainda não avaliado_"
    private val META = Regex("""^<!-- (\S+) (\{.*}) -->$""")
    private val AI_BOX = Regex("""^- \[([ xX])] \*\*Atendido \(IA\)\*\*""")

    private class RecordMeta(val number: Int, val title: String, val url: String, val head: String, val base: String, val checkedAt: String?)

    private class RequestMeta(
        val id: String, val path: String, val target: String, val start: Int, val end: Int, val snippet: String,
        val text: String, val commit: String, val comment: Long?, val url: String?, val sentAt: String,
        val code: String, val codeCommit: String?, val thread: String, val lastReply: String?,
        val suggestion: String? = null,
    )

    fun render(record: PullRequestRecord): String = buildString {
        appendLine("# Pedidos de alteração do PR #${record.number}: ${record.title.oneLine()}")
        appendLine()
        appendLine("<!-- $RECORD_TAG ${gson.toJson(RecordMeta(record.number, record.title, record.url, record.headRef, record.baseRef, record.checkedAt))} -->")
        appendLine()
        appendLine("> **Instruções para a IA** (valem para qualquer assistente ou modelo): este arquivo guarda os pedidos de")
        appendLine("> alteração enviados ao pull request #${record.number} (`${record.headRef}` → `${record.baseRef}`). Para cada pedido,")
        appendLine("> confira no código atual da branch `${record.headRef}` se a alteração pedida foi feita; se o checkout local")
        appendLine("> estiver desatualizado, atualize a branch antes. Marque `[x]` em $AI_LABEL quando foi feita e deixe `[ ]`")
        appendLine("> quando não foi, e escreva o motivo numa linha em $NOTE_LABEL. Quando o pedido tiver **Código sugerido**,")
        appendLine("> ele é um exemplo: uma solução equivalente também atende. Não altere o código nem o restante deste")
        appendLine("> arquivo.")
        appendLine()
        appendLine("PR: ${record.url}")
        appendLine()
        appendLine(record.checkedAt?.let { "Verificado em ${formatDate(it)}." } ?: "Ainda não verificado.")
        appendLine()
        appendLine("| Pedido | Onde | Código | Conversa | IA |")
        appendLine("| --- | --- | --- | --- | --- |")
        record.requests.forEach { request ->
            appendLine("| ${request.id} | `${request.path}`, ${request.location} | ${request.code.label} | ${request.thread.label} | ${request.ai.label} |")
        }
        record.requests.forEach { request ->
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## ${request.id} — `${request.path}`, ${request.location}")
            appendLine()
            appendLine("<!-- $REQUEST_TAG ${gson.toJson(meta(request))} -->")
            appendLine()
            appendLine("**Pedido:**")
            appendLine()
            appendLine(request.text.trim())
            if (request.snippet.isNotBlank()) {
                appendLine()
                appendCode(request.snippet, request.path)
            }
            if (request.suggestion.isNotBlank()) {
                appendLine()
                appendLine("**Código sugerido:**")
                appendLine()
                appendCode(request.suggestion, request.path)
            }
            appendLine()
            appendLine("- **Enviado:** ${formatDate(request.sentAt)}, no commit `${request.commit.take(7)}`${request.url?.let { " · [ver no GitHub]($it)" }.orEmpty()}")
            appendLine("- **Código:** ${request.code.label}${request.codeCommit?.let { ", no commit `${it.take(7)}`" }.orEmpty()}")
            appendLine("- **Conversa no PR:** ${request.thread.label}${request.lastReply?.let { ". Última resposta: $it" }.orEmpty()}")
            appendLine("- [${if (request.ai == AiVerdict.DONE) "x" else " "}] $AI_LABEL")
            appendLine()
            appendLine("$NOTE_LABEL ${request.aiNote.ifBlank { NOTE_PLACEHOLDER }}")
        }
    }

    private fun StringBuilder.appendCode(code: String, path: String) {
        val fence = if ("```" in code) "````" else "```"
        appendLine("$fence${path.substringAfterLast('.', "")}")
        appendLine(code.trimEnd())
        appendLine(fence)
    }

    fun parse(markdown: String): PullRequestRecord? {
        val lines = markdown.lines()
        val recordMeta = lines.firstNotNullOfOrNull { line -> metaOf(line, RECORD_TAG) }
            ?.let { gson.fromJson(it, RecordMeta::class.java) } ?: return null
        val requests = mutableListOf<ChangeRequest>()
        var current: RequestMeta? = null
        var checked = false
        var note = ""
        fun flush() {
            val meta = current ?: return
            val verdict = when {
                checked -> AiVerdict.DONE
                note.isNotBlank() -> AiVerdict.NOT_DONE
                else -> AiVerdict.PENDING
            }
            requests += request(meta, verdict, note)
        }
        for (line in lines) {
            val trimmed = line.trim()
            metaOf(trimmed, REQUEST_TAG)?.let { json ->
                flush()
                current = gson.fromJson(json, RequestMeta::class.java)
                checked = false
                note = ""
                return@let
            }
            if (current == null) continue
            AI_BOX.find(trimmed)?.let { checked = it.groupValues[1].isNotBlank() }
            if (trimmed.startsWith(NOTE_LABEL)) {
                note = trimmed.removePrefix(NOTE_LABEL).trim().takeUnless { it == NOTE_PLACEHOLDER }.orEmpty()
            }
        }
        flush()
        return PullRequestRecord(recordMeta.number, recordMeta.title, recordMeta.url, recordMeta.head, recordMeta.base, requests, recordMeta.checkedAt)
    }

    private fun metaOf(line: String, tag: String): String? =
        META.matchEntire(line.trim())?.takeIf { it.groupValues[1] == tag }?.groupValues?.get(2)
            ?.takeIf { runCatching { JsonParser.parseString(it) }.isSuccess }

    private fun meta(request: ChangeRequest) = RequestMeta(
        request.id, request.path, request.target.name, request.startLine, request.endLine, request.snippet, request.text,
        request.commit, request.commentId, request.url, request.sentAt, request.code.name, request.codeCommit,
        request.thread.name, request.lastReply, request.suggestion.ifBlank { null },
    )

    private fun request(meta: RequestMeta, ai: AiVerdict, note: String) = ChangeRequest(
        id = meta.id,
        path = meta.path,
        target = enumOr(meta.target, CommentTarget.LINES),
        startLine = meta.start,
        endLine = meta.end,
        snippet = meta.snippet.orEmpty(),
        text = meta.text.orEmpty(),
        commit = meta.commit.orEmpty(),
        commentId = meta.comment,
        url = meta.url,
        sentAt = meta.sentAt.orEmpty(),
        code = enumOr(meta.code, CodeStatus.NOT_CHECKED),
        codeCommit = meta.codeCommit,
        thread = enumOr(meta.thread, if (meta.comment == null) ThreadStatus.NONE else ThreadStatus.NOT_CHECKED),
        lastReply = meta.lastReply,
        ai = ai,
        aiNote = note,
        suggestion = meta.suggestion.orEmpty(),
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private fun formatDate(iso: String): String = runCatching { LocalDateTime.parse(iso).format(DATE) }.getOrDefault(iso)

    private fun String.oneLine() = lines().joinToString(" ") { it.trim() }.trim()
}
