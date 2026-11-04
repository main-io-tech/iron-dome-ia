package com.mio.irondome.patrulha

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class GitHubException(message: String, val status: Int = 0, val oauthRestricted: Boolean = false) : Exception(message) {

    companion object {
        /** Login pelo navegador (OAuth App da JetBrains) numa organização que só libera apps aprovados. */
        fun isOauthRestriction(message: String) = "OAuth App access restrictions" in message

        const val OAUTH_RESTRICTED = "A organização do repositório bloqueia o login do IntelliJ pelo navegador (OAuth App). " +
            "Entre com um token pessoal (classic, escopo repo, autorizado no SSO da organização, se houver) " +
            "ou peça a um admin da organização para aprovar o app JetBrains IDE Integration."
    }
}

/**
 * API REST e GraphQL do GitHub com o token da conta do IntelliJ. Faz rede de forma síncrona: chame fora
 * da EDT. [apiUrl] e [graphQlUrl] vêm do servidor da conta, o que cobre também o GitHub Enterprise.
 */
class GitHubClient(private val apiUrl: String, private val graphQlUrl: String, private val token: String) {

    /** Comentário de linha numa revisão; [startLine] só quando o trecho tem mais de uma linha. Linhas 1-based. */
    class InlineComment(val path: String, val startLine: Int?, val line: Int, val body: String)

    /** [subjectType] é `line` nos comentários de linha e `file` nos do arquivo inteiro. */
    class PostedComment(
        val id: Long,
        val url: String,
        val path: String,
        val line: Int?,
        val body: String,
        val subjectType: String = "line",
        val updatedAt: String? = null,
    )

    class Review(val id: Long, val url: String)

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun currentUser(): String = request("GET", "/user").asJsonObject.string("login")

    fun openPullRequests(repo: GitHubRepo): List<PullRequest> =
        request("GET", "${repoPath(repo)}/pulls?state=open&sort=updated&direction=desc&per_page=100")
            .asJsonArray.map { pullRequest(it.asJsonObject) }

    fun pullRequest(repo: GitHubRepo, number: Int): PullRequest =
        pullRequest(request("GET", "${repoPath(repo)}/pulls/$number").asJsonObject)

    fun files(repo: GitHubRepo, number: Int): List<ChangedFile> {
        val files = mutableListOf<ChangedFile>()
        for (page in 1..MAX_PAGES) {
            val batch = request("GET", "${repoPath(repo)}/pulls/$number/files?per_page=100&page=$page").asJsonArray
            batch.forEach { item ->
                val file = item.asJsonObject
                files += ChangedFile(
                    path = file.string("filename"),
                    status = file.string("status"),
                    patch = file.optString("patch"),
                    additions = file.get("additions")?.takeUnless { it is JsonNull }?.asInt ?: 0,
                    deletions = file.get("deletions")?.takeUnless { it is JsonNull }?.asInt ?: 0,
                    previousPath = file.optString("previous_filename"),
                )
            }
            if (batch.size() < 100) break
        }
        return files
    }

    /** O ancestral comum de [base] e [head]: o GitHub compara o PR com ele, não com a ponta da base. */
    fun mergeBase(repo: GitHubRepo, base: String, head: String): String =
        request("GET", "${repoPath(repo)}/compare/${encode(base)}...${encode(head)}").asJsonObject
            .getAsJsonObject("merge_base_commit").string("sha")

    /** Conteúdo do arquivo em [ref], ou nulo se ele não existe lá. */
    fun fileContent(repo: GitHubRepo, path: String, ref: String): String? {
        val encodedPath = path.split('/').joinToString("/") { encode(it).replace("+", "%20") }
        val response = send("GET", apiUrl.trimEnd('/') + "${repoPath(repo)}/contents/$encodedPath?ref=${encode(ref)}", null, RAW)
        if (response.statusCode() == 404) return null
        check(response)
        return response.body()
    }

    /** [event] é `REQUEST_CHANGES` ou `COMMENT`. */
    fun createReview(
        repo: GitHubRepo,
        number: Int,
        commitSha: String,
        body: String,
        event: String,
        comments: List<InlineComment>,
    ): Review {
        val payload = JsonObject().apply {
            addProperty("commit_id", commitSha)
            addProperty("event", event)
            if (body.isNotBlank()) addProperty("body", body)
            add("comments", JsonArray().apply {
                comments.forEach { comment ->
                    add(JsonObject().apply {
                        addProperty("path", comment.path)
                        addProperty("line", comment.line)
                        addProperty("side", "RIGHT")
                        if (comment.startLine != null && comment.startLine < comment.line) {
                            addProperty("start_line", comment.startLine)
                            addProperty("start_side", "RIGHT")
                        }
                        addProperty("body", comment.body)
                    })
                }
            })
        }
        val review = request("POST", "${repoPath(repo)}/pulls/$number/reviews", payload).asJsonObject
        return Review(review.long("id"), review.optString("html_url").orEmpty())
    }

    fun reviewComments(repo: GitHubRepo, number: Int, reviewId: Long): List<PostedComment> =
        request("GET", "${repoPath(repo)}/pulls/$number/reviews/$reviewId/comments?per_page=100")
            .asJsonArray.map { postedComment(it.asJsonObject) }

    /** Comentário sobre o arquivo inteiro, fora de qualquer linha do diff. */
    fun createFileComment(repo: GitHubRepo, number: Int, commitSha: String, path: String, body: String): PostedComment {
        val payload = JsonObject().apply {
            addProperty("commit_id", commitSha)
            addProperty("path", path)
            addProperty("subject_type", "file")
            addProperty("body", body)
        }
        return postedComment(request("POST", "${repoPath(repo)}/pulls/$number/comments", payload).asJsonObject)
    }

    fun reviewComment(repo: GitHubRepo, id: Long): PostedComment =
        postedComment(request("GET", "${repoPath(repo)}/pulls/comments/$id").asJsonObject)

    fun updateReviewComment(repo: GitHubRepo, id: Long, body: String): PostedComment =
        postedComment(request("PATCH", "${repoPath(repo)}/pulls/comments/$id", JsonObject().apply { addProperty("body", body) }).asJsonObject)

    /** Apagado antes, por outra pessoa no GitHub, também conta como apagado. */
    fun deleteReviewComment(repo: GitHubRepo, id: Long) {
        val response = send("DELETE", apiUrl.trimEnd('/') + "${repoPath(repo)}/pulls/comments/$id", null, JSON)
        if (response.statusCode() == 404) return
        check(response)
    }

    /** Todos os comentários de linha e de arquivo do PR, de todas as revisões, como estão agora no GitHub. */
    fun pullRequestComments(repo: GitHubRepo, number: Int): List<PostedComment> {
        val comments = mutableListOf<PostedComment>()
        for (page in 1..MAX_PAGES) {
            val batch = request("GET", "${repoPath(repo)}/pulls/$number/comments?per_page=100&page=$page").asJsonArray
            batch.forEach { comments += postedComment(it.asJsonObject) }
            if (batch.size() < 100) break
        }
        return comments
    }

    fun reviewThreads(repo: GitHubRepo, number: Int): List<ReviewThread> {
        val query = """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                pullRequest(number: ${'$'}number) {
                  reviewThreads(first: 100) {
                    nodes { isResolved isOutdated comments(first: 50) { nodes { databaseId body createdAt author { login } } } }
                  }
                }
              }
            }
        """.trimIndent()
        val variables = JsonObject().apply {
            addProperty("owner", repo.owner)
            addProperty("name", repo.name)
            addProperty("number", number)
        }
        val payload = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }
        val response = send("POST", graphQlUrl, payload, JSON)
        check(response)
        val json = parse(response).asJsonObject
        json.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.optString("message")?.let {
            if (GitHubException.isOauthRestriction(it)) throw GitHubException(GitHubException.OAUTH_RESTRICTED, 403, oauthRestricted = true)
            throw GitHubException("GitHub: $it")
        }
        val nodes = json.getAsJsonObject("data")?.getAsJsonObject("repository")?.getAsJsonObject("pullRequest")
            ?.getAsJsonObject("reviewThreads")?.getAsJsonArray("nodes") ?: return emptyList()
        return nodes.mapNotNull { node ->
            val thread = node.asJsonObject
            val comments = thread.getAsJsonObject("comments")?.getAsJsonArray("nodes")?.map { it.asJsonObject }.orEmpty()
            val first = comments.firstOrNull() ?: return@mapNotNull null
            ReviewThread(
                firstCommentId = first.long("databaseId"),
                resolved = thread.get("isResolved")?.asBoolean == true,
                outdated = thread.get("isOutdated")?.asBoolean == true,
                replies = comments.drop(1).map { reply ->
                    ThreadReply(
                        author = reply.getAsJsonObject("author")?.optString("login") ?: "fantasma",
                        body = reply.optString("body").orEmpty(),
                        createdAt = reply.optString("createdAt").orEmpty(),
                    )
                },
            )
        }
    }

    private fun pullRequest(json: JsonObject): PullRequest {
        val head = json.getAsJsonObject("head")
        return PullRequest(
            number = json["number"].asInt,
            title = json.string("title"),
            author = json.getAsJsonObject("user")?.optString("login") ?: "fantasma",
            headRef = head.string("ref"),
            headSha = head.string("sha"),
            baseRef = json.getAsJsonObject("base").string("ref"),
            baseSha = json.getAsJsonObject("base").optString("sha").orEmpty(),
            url = json.string("html_url"),
            updatedAt = json.optString("updated_at").orEmpty(),
            draft = json.get("draft")?.takeUnless { it is JsonNull }?.asBoolean == true,
        )
    }

    private fun postedComment(json: JsonObject) = PostedComment(
        id = json.long("id"),
        url = json.optString("html_url").orEmpty(),
        path = json.optString("path").orEmpty(),
        line = json.get("line")?.takeUnless { it is JsonNull }?.asInt,
        body = json.optString("body").orEmpty(),
        subjectType = json.optString("subject_type") ?: "line",
        updatedAt = json.optString("updated_at"),
    )

    private fun request(method: String, path: String, payload: JsonObject? = null): JsonElement {
        val response = send(method, apiUrl.trimEnd('/') + path, payload, JSON)
        check(response)
        return parse(response)
    }

    private fun send(method: String, url: String, payload: JsonObject?, accept: String): HttpResponse<String> {
        val body = payload?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", accept)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", "Iron-Dome-IA")
            .apply { if (payload != null) header("Content-Type", "application/json") }
            .method(method, body)
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun check(response: HttpResponse<String>) {
        val status = response.statusCode()
        if (status in 200..299) return
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
        val details = json?.getAsJsonArray("errors")?.mapNotNull { error ->
            if (error.isJsonObject) error.asJsonObject.optString("message") else error.asString
        }.orEmpty()
        val message = listOfNotNull(json?.optString("message")).plus(details).joinToString(": ").ifEmpty { "HTTP $status" }
        if (status == 403 && GitHubException.isOauthRestriction(message)) {
            throw GitHubException(GitHubException.OAUTH_RESTRICTED, status, oauthRestricted = true)
        }
        throw GitHubException(
            when (status) {
                401 -> "O token do GitHub foi recusado. Entre de novo em Settings > Version Control > GitHub."
                403 -> "O GitHub negou o acesso: $message"
                404 -> "Não encontrado no GitHub, ou a conta não tem acesso ao repositório: $message"
                else -> "GitHub: $message"
            },
            status,
        )
    }

    private fun parse(response: HttpResponse<String>): JsonElement =
        runCatching { JsonParser.parseString(response.body()) }.getOrNull()
            ?: throw GitHubException("Resposta inesperada do GitHub (HTTP ${response.statusCode()}).")

    private fun repoPath(repo: GitHubRepo) = "/repos/${repo.owner}/${repo.name}"

    private fun encode(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8)

    private fun JsonObject.string(key: String): String = optString(key) ?: throw GitHubException("Resposta do GitHub sem o campo $key.")

    private fun JsonObject.optString(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.asString

    private fun JsonObject.long(key: String): Long = get(key)?.takeUnless { it is JsonNull }?.asLong
        ?: throw GitHubException("Resposta do GitHub sem o campo $key.")

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val JSON = "application/vnd.github+json"
        const val RAW = "application/vnd.github.raw+json"
        const val MAX_PAGES = 30
    }
}
