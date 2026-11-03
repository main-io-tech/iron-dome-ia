package com.mio.irondome.caixapreta

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class LinearException(message: String) : Exception(message)

/** API GraphQL do Linear com uma API key pessoal. Faz rede de forma síncrona: chame fora da EDT. */
class LinearClient(private val apiKey: String) {

    class Team(val id: String, val key: String, val name: String)

    class Project(val id: String, val name: String, val teams: List<Team>) {

        /** O time de [key] ou, sem chave, o primeiro do projeto. */
        fun teamFor(key: String?): Team {
            if (key == null) return teams.firstOrNull() ?: throw LinearException("O projeto $name não tem nenhum time no Linear.")
            return teams.firstOrNull { it.key == key }
                ?: throw LinearException("O time $key não participa do projeto $name. Times do projeto: ${teams.joinToString { it.key }}.")
        }
    }

    class Issue(val identifier: String, val url: String)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Pelo id curto que aparece no fim do slug da URL, como `f447654e94fc`. */
    fun project(slugId: String): Project {
        val data = graphql(
            "query(\$slugId: String!) { projects(filter: { slugId: { eq: \$slugId } }) { " +
                "nodes { id name teams { nodes { id key name } } } } }",
            JsonObject().apply { addProperty("slugId", slugId) },
        )
        val node = data.getAsJsonObject("projects")?.getAsJsonArray("nodes")?.firstOrNull()?.asJsonObject
            ?: throw LinearException("Projeto não encontrado no Linear, ou a API key não tem acesso a ele. Confira em Settings > Tools > Iron Dome IA.")
        val teams = node.getAsJsonObject("teams")?.getAsJsonArray("nodes")?.map { team ->
            team.asJsonObject.let { Team(it["id"].asString, it["key"].asString, it["name"].asString) }
        }.orEmpty()
        return Project(node["id"].asString, node["name"].asString, teams)
    }

    /** Envia o arquivo ao armazenamento do Linear e devolve o endereço para citar no Markdown da issue. */
    fun upload(file: Path, contentType: String): String {
        val data = graphql(
            "mutation(\$contentType: String!, \$filename: String!, \$size: Int!) { " +
                "fileUpload(contentType: \$contentType, filename: \$filename, size: \$size) { " +
                "success uploadFile { uploadUrl assetUrl headers { key value } } } }",
            JsonObject().apply {
                addProperty("contentType", contentType)
                addProperty("filename", file.fileName.toString())
                addProperty("size", Files.size(file))
            },
        )
        val upload = data.getAsJsonObject("fileUpload")?.getAsJsonObject("uploadFile")
            ?: throw LinearException("O Linear não liberou o envio de ${file.fileName}.")
        val request = HttpRequest.newBuilder(URI(upload["uploadUrl"].asString))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", contentType)
            .header("Cache-Control", "public, max-age=31536000")
        upload.getAsJsonArray("headers")?.forEach { header ->
            request.header(header.asJsonObject["key"].asString, header.asJsonObject["value"].asString)
        }
        val response = http.send(request.PUT(HttpRequest.BodyPublishers.ofFile(file)).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw LinearException("Falha ao enviar ${file.fileName} ao Linear (HTTP ${response.statusCode()}).")
        }
        return upload["assetUrl"].asString
    }

    fun createIssue(teamId: String, projectId: String, title: String, description: String): Issue {
        val data = graphql(
            "mutation(\$input: IssueCreateInput!) { issueCreate(input: \$input) { success issue { identifier url } } }",
            JsonObject().apply {
                add("input", JsonObject().apply {
                    addProperty("teamId", teamId)
                    addProperty("projectId", projectId)
                    addProperty("title", title)
                    addProperty("description", description)
                })
            },
        )
        val issue = data.getAsJsonObject("issueCreate")?.getAsJsonObject("issue")
            ?: throw LinearException("O Linear não criou a issue.")
        return Issue(issue["identifier"].asString, issue["url"].asString)
    }

    private fun graphql(query: String, variables: JsonObject): JsonObject {
        val body = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }
        val request = HttpRequest.newBuilder(URI(ENDPOINT))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
            ?: throw LinearException("Resposta inesperada do Linear (HTTP ${response.statusCode()}).")
        json.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString?.let { message ->
            if (response.statusCode() == 401 || message.contains("authentication", ignoreCase = true)) {
                throw LinearException("API key do Linear inválida ou sem acesso. Confira em Settings > Tools > Iron Dome IA.")
            }
            throw LinearException("Linear: $message")
        }
        return json.getAsJsonObject("data") ?: throw LinearException("Resposta vazia do Linear (HTTP ${response.statusCode()}).")
    }

    private companion object {
        const val ENDPOINT = "https://api.linear.app/graphql"
    }
}
