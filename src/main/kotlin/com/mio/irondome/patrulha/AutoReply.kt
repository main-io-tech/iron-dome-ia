package com.mio.irondome.patrulha

import com.google.gson.JsonObject
import com.mio.irondome.interceptor.AgentEvents

/** O que a IA decidiu sobre a última resposta do dev numa conversa. */
enum class AutoDecision {
    /** A conversa continua: a resposta vai para o GitHub e o monitor espera o dev de novo. */
    REPLY,
    /** Chegou a uma conclusão: a resposta final vai para o GitHub e a conversa é resolvida. */
    CONCLUDE,
    /** Precisa de quem revisa (decisão de produto, conflito, tom): nada é publicado e o revisor é avisado. */
    HUMAN,
}

data class AutoAnswer(val decision: AutoDecision, val reply: String, val reason: String)

/** Onde está cada conversa no modo automático, para o painel mostrar. */
enum class AutoStatus(val label: String) {
    WAITING("IA aguardando o dev"),
    CONCLUDED("IA concluiu"),
    NEEDS_YOU("IA pede você"),
    LIMIT("IA no limite de rodadas"),
}

/**
 * As respostas automáticas da IA nas conversas de quem revisa: quais conversas esperam resposta, as instruções que
 * a IA lê e a leitura do JSON que ela devolve.
 */
object AutoReply {

    /** Rodadas da IA por conversa: passou disso, a conversa volta para quem revisa. */
    const val MAX_ROUNDS = 5

    /** Linhas do arquivo no head do PR, antes e depois da linha comentada, que a IA recebe. */
    private const val CONTEXT_LINES = 25
    private const val FILE_PREVIEW_LINES = 120

    /** No fim de cada resposta publicada, para o dev saber que quem respondeu foi a IA. */
    fun footer(me: String) = "\n\n<sub>🤖 Resposta automática da IA (Iron Dome) em nome de @$me.</sub>"

    /** Chave da conversa no estado do modo automático, como `12:thread:345`. */
    fun key(number: Int, discussion: Discussion) = "$number:${discussion.key}"

    /**
     * As conversas de linha que [me] abriu e que esperam a IA: abertas, com a última palavra de outra pessoa,
     * publicada, ainda não tratada ([handled] tem os ids dos comentários já respondidos) e fora de [stopped].
     */
    fun pending(number: Int, discussions: List<Discussion>, me: String, handled: Set<Long>, stopped: Set<String>): List<Discussion> =
        discussions.filter { discussion ->
            val last = discussion.comments.last()
            discussion.kind == DiscussionKind.THREAD && discussion.nodeId.isNotEmpty() && !discussion.resolved &&
                discussion.first.author.equals(me, ignoreCase = true) &&
                !last.author.equals(me, ignoreCase = true) && !last.pending &&
                last.id !in handled && key(number, discussion) !in stopped
        }

    /**
     * [code] é o arquivo no head do PR (nulo se saiu do PR); [request], o pedido do `.md` que abriu a conversa,
     * quando há; [round], a rodada desta resposta, de 1 a [MAX_ROUNDS].
     */
    fun instructions(
        pr: PullRequest,
        discussion: Discussion,
        me: String,
        code: String?,
        request: ChangeRequest?,
        round: Int,
    ): String = buildString {
        val where = "`${discussion.path}`" + (discussion.line?.let { ", linha $it" } ?: ", arquivo inteiro")
        appendLine("# Resposta automática no PR #${pr.number}: ${pr.title}")
        appendLine()
        appendLine("Você responde, em nome do revisor @$me, a uma conversa de revisão de código no GitHub, em $where.")
        appendLine("O PR leva `${pr.headRef}` para `${pr.baseRef}`, de @${pr.author}. O revisor comentou, alguém respondeu, e agora")
        appendLine("é a sua vez. O objetivo é chegar a uma conclusão: o problema foi corrigido, a explicação do dev resolve a dúvida,")
        appendLine("ou os dois combinaram um próximo passo (como uma issue para depois).")
        appendLine()
        appendLine("Regras:")
        appendLine()
        appendLine("- **Não altere nenhum arquivo.** O código local pode não ser o do PR: use o código do head abaixo.")
        appendLine("- Avalie a última resposta com honestidade. Se o dev tem razão, aceite; não insista só para ganhar a discussão.")
        appendLine("- Se o dev disse que corrigiu, confira no código do head abaixo. Corrigido: conclua. Não corrigido ou")
        appendLine("  só em parte: explique com calma o que ainda falta.")
        appendLine("- Se o dev só disse que vai corrigir, agradeça em uma frase e continue (`reply`), para conferir depois.")
        appendLine("- Use `human` quando a conversa pedir uma decisão do revisor: regra de negócio, prioridade, escopo do PR,")
        appendLine("  divergência que não se resolve com argumento técnico, ou tom tenso. Nesse caso, não escreva a resposta.")
        appendLine("- Esta é a rodada $round de no máximo $MAX_ROUNDS. Na última, conclua ou passe para `human`.")
        appendLine()
        appendLine("## Como escrever a resposta")
        appendLine()
        appendLine("Em português do Brasil, em Markdown do GitHub, como um colega mais experiente e paciente: respeitosa,")
        appendLine("direta e explicativa, entre 2 e 6 frases. Fale do código, não da pessoa. Cite variáveis e métodos entre crases;")
        appendLine("código de mais de uma linha vai em bloco cercado por três crases, com a linguagem. Sem títulos, tabelas ou HTML.")
        appendLine("Não assine nem diga que é uma IA: o plugin acrescenta isso.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json, por exemplo:")
        appendLine()
        appendLine("```json")
        appendLine("""{"decision": "reply", "reply": "Faz sentido, mas `itens` ainda pode vir vazio quando…", "reason": "o caso da lista vazia continua"}""")
        appendLine("```")
        appendLine()
        appendLine("- `decision`: `reply` (a conversa continua), `conclude` (chegou a uma conclusão: `reply` é a mensagem final, e a")
        appendLine("  conversa será marcada como resolvida) ou `human` (o revisor precisa responder; `reply` vazio).")
        appendLine("- `reason`: uma frase para o revisor, explicando a decisão.")
        appendLine()
        if (request != null) {
            appendLine("## O pedido original do revisor")
            appendLine()
            if (request.snippet.isNotBlank()) {
                appendLine("Trecho comentado, no envio (commit `${request.commit.take(7)}`):")
                appendLine()
                appendCode(request.snippet, discussion.path.orEmpty())
                appendLine()
            }
            if (request.suggestion.isNotBlank()) {
                appendLine("Código que o revisor sugeriu:")
                appendLine()
                appendCode(request.suggestion, discussion.path.orEmpty())
                appendLine()
            }
        }
        appendLine("## A conversa até agora")
        discussion.comments.forEach { comment ->
            appendLine()
            val who = if (comment.author.equals(me, ignoreCase = true)) "@${comment.author} (revisor)" else "@${comment.author}"
            appendLine("### $who · ${Discussions.formatDate(comment.createdAt)}")
            appendLine()
            appendLine(comment.body.trim().ifEmpty { "_Sem texto._" })
        }
        appendLine()
        appendLine("## O código no head do PR (commit `${pr.headSha.take(7)}`)")
        appendLine()
        if (code == null) {
            appendLine("O arquivo não existe mais no head do PR.")
        } else {
            appendLine("Linhas numeradas; a conversa está ${discussion.line?.let { "na linha $it" } ?: "no arquivo inteiro"}.")
            appendLine()
            appendCode(excerpt(code, discussion), discussion.path.orEmpty())
        }
    }

    /** Em volta da linha comentada, com o número de cada linha; no arquivo inteiro, o começo dele. */
    fun excerpt(code: String, discussion: Discussion): String {
        val lines = code.replace("\r\n", "\n").split('\n')
        val line = discussion.line
        val range = if (line == null) {
            0 until minOf(lines.size, FILE_PREVIEW_LINES)
        } else {
            val start = ((discussion.startLine ?: line) - 1 - CONTEXT_LINES).coerceAtLeast(0)
            start until minOf(lines.size, line + CONTEXT_LINES)
        }
        val width = (range.last + 1).toString().length
        return range.joinToString("\n") { "${(it + 1).toString().padStart(width)}| ${lines[it]}" }
    }

    /** A decisão da IA; nula se a resposta não trouxe o JSON pedido, ou se pediu para responder sem texto. */
    fun parse(answer: String): AutoAnswer? {
        val json = AgentEvents.extractJson(answer)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val decision = when (json.text("decision")?.trim()?.lowercase()) {
            "reply" -> AutoDecision.REPLY
            "conclude" -> AutoDecision.CONCLUDE
            "human" -> AutoDecision.HUMAN
            else -> return null
        }
        val reply = json.text("reply")?.trim().orEmpty()
        if (decision != AutoDecision.HUMAN && reply.isEmpty()) return null
        return AutoAnswer(decision, reply, json.text("reason")?.trim().orEmpty())
    }

    private fun StringBuilder.appendCode(code: String, path: String) {
        val fence = if ("```" in code) "````" else "```"
        appendLine("$fence${path.substringAfterLast('.', "")}")
        appendLine(code.trimEnd())
        appendLine(fence)
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
