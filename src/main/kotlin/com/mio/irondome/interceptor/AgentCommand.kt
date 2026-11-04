package com.mio.irondome.interceptor

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.Normalizer

/** CLI de agente de IA que o Interceptador chama, rodando na máquina do usuário sobre o projeto aberto. */
enum class AgentProvider(val title: String, val executables: List<String>, val installHint: String) {
    CURSOR(
        "Cursor",
        listOf("agent", "cursor-agent"),
        "Instale o Cursor CLI (no PowerShell: irm 'https://cursor.com/install?win32=true' | iex) e rode agent login, ou informe uma chave de API do Cursor.",
    ),
    ANTIGRAVITY(
        "Antigravity",
        listOf("agy"),
        "Instale o Antigravity CLI e entre uma vez com agy, no terminal: o modo sem janela usa a credencial guardada.",
    ),
}

/**
 * Uma tarefa para a IA. [prompt] fica curto e numa linha só, porque no Windows ele passa pelo cmd.exe; as
 * instruções longas vão num arquivo que o prompt manda ler. [write] deixa a IA alterar arquivos.
 */
data class AgentTask(val title: String, val prompt: String, val write: Boolean)

object AgentCommand {

    /** O comando completo; [executable] já resolvido, [model] vazio usa o padrão do CLI. */
    fun build(provider: AgentProvider, executable: String, task: AgentTask, workspace: String, model: String?, timeoutMinutes: Int): List<String> {
        val prompt = oneLine(task.prompt)
        val chosenModel = model?.trim()?.takeIf { it.isNotEmpty() }
        return when (provider) {
            AgentProvider.CURSOR -> buildList {
                add(executable)
                add("-p")
                add("--output-format")
                add("stream-json")
                add("--trust")
                add("--workspace")
                add(workspace)
                if (task.write) add("--force") else addAll(listOf("--mode", "ask"))
                chosenModel?.let { addAll(listOf("--model", it)) }
                add(prompt)
            }
            AgentProvider.ANTIGRAVITY -> buildList {
                add(executable)
                add("-p")
                add(prompt)
                add("--output-format")
                add("stream-json")
                add("--print-timeout")
                add("${timeoutMinutes}m")
                chosenModel?.let { addAll(listOf("--model", it)) }
            }
        }
    }

    /**
     * Quebras de linha e os caracteres especiais do cmd.exe viram espaço, e os acentos saem: no Windows, o
     * prompt passa pela página de código do console. As instruções com acento vão nos arquivos, em UTF-8.
     */
    fun oneLine(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[\r\n]+"""), " ").replace(Regex("""[&|<>^%"]"""), " ").replace(Regex(" {2,}"), " ").trim()
}

/** Um evento do `stream-json` dos CLIs: uma linha de JSON por evento. */
sealed interface AgentEvent {
    data class Init(val model: String?) : AgentEvent
    data class Text(val text: String) : AgentEvent
    /** [kind] é o tipo da ferramenta sem o sufixo, como `read`, `write` ou `edit`. */
    data class Tool(val started: Boolean, val kind: String, val path: String?) : AgentEvent
    data class Result(val text: String, val error: Boolean) : AgentEvent
    /** Linha que não é JSON ou de um formato desconhecido: entra no registro como veio. */
    data class Other(val line: String) : AgentEvent
}

object AgentEvents {

    fun parse(line: String): AgentEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return AgentEvent.Other(text)
        return when (json.str("type")) {
            "system" -> AgentEvent.Init(json.str("model"))
            "assistant" -> messageText(json)?.let(AgentEvent::Text)
            "tool_call" -> tool(json)
            "result" -> AgentEvent.Result(
                json.str("result").orEmpty(),
                json.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean == true || json.str("subtype") == "error",
            )
            "user", "thinking" -> null
            else -> AgentEvent.Other(text)
        }
    }

    /**
     * Ferramentas como `readToolCall`, `writeToolCall` ou `editToolCall`, com o caminho em `args.path`; os
     * outros campos de `tool_call`, como `toolCallId`, ficam de fora.
     */
    private fun tool(json: JsonObject): AgentEvent? {
        val call = json.getAsJsonObject("tool_call") ?: return null
        val (name, body) = call.entrySet().firstOrNull { it.key.endsWith("ToolCall") } ?: call.entrySet().firstOrNull { it.value.isJsonObject }
            ?: return null
        val kind = name.removeSuffix("ToolCall").ifEmpty { name }
        val args = (body as? JsonObject)?.getAsJsonObject("args")
        val path = args?.str("path") ?: args?.str("file_path") ?: args?.str("filePath")
        return AgentEvent.Tool(json.str("subtype") == "started", kind, path)
    }

    private fun messageText(json: JsonObject): String? {
        val content = json.getAsJsonObject("message")?.get("content") ?: return null
        val text = when {
            content.isJsonArray -> (content as JsonArray).mapNotNull { block -> (block as? JsonObject)?.str("text") }.joinToString("")
            content.isJsonPrimitive -> content.asString
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /**
     * O JSON pedido na resposta da IA: o bloco ```json, ou do primeiro `[`/`{` ao último `]`/`}`. Nulo se não
     * houver um JSON válido.
     */
    fun extractJson(answer: String): JsonElement? {
        val fenced = Regex("""```(?:json)?\s*\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL).findAll(answer).map { it.groupValues[1] }
        val candidates = fenced + sequenceOf('[' to ']', '{' to '}').mapNotNull { (open, close) ->
            val start = answer.indexOf(open)
            val end = answer.lastIndexOf(close)
            if (start >= 0 && end > start) answer.substring(start, end + 1) else null
        }
        return candidates.firstNotNullOfOrNull { runCatching { JsonParser.parseString(it) }.getOrNull()?.takeIf { e -> e.isJsonArray || e.isJsonObject } }
    }
}
