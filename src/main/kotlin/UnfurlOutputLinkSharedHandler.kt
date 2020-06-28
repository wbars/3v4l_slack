import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.slack.api.app_backend.events.handler.LinkSharedHandler
import com.slack.api.app_backend.events.payload.LinkSharedPayload
import com.slack.api.model.event.LinkSharedEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

val unfurl = URI.create("https://slack.com/api/chat.unfurl")
const val applicationJsonHeader = "application/json"

class UnfurlOutputLinkSharedHandler(val token: String) : LinkSharedHandler() {
    private fun createSection(code: String): JsonObject {
        val unfurlsR = JsonObject()
        val codeObj = JsonObject()
        codeObj.addProperty("type", "mrkdwn")
        codeObj.addProperty("text", code)
        unfurlsR.addProperty("type", "section")
        unfurlsR.add("text", codeObj)
        return unfurlsR
    }

    private fun createBlocks(evalOutput: EvalOutput): JsonArray {
        val blocks = JsonArray()
        val codeSection = evalOutput.code.codeBlock()
        blocks.add(createSection(codeSection))
        for (runResult in evalOutput.runResults) {
            blocks.add(createSection("*Output for ${runResult.versions}:*\n${runResult.output.codeBlock()}"))
        }
        return blocks
    }

    override fun handle(payload: LinkSharedPayload?) {
        val event = payload?.event ?: return
        val links = event.links ?: return
        val client = HttpClient.newHttpClient()
        for (link in links) {
            unfurl(client, link, event)
        }
    }

    private fun unfurl(client: HttpClient, link: LinkSharedEvent.Link, event: LinkSharedEvent) {
        client
                .sendAsync(createGetEvalOutputJsonRequest(link), HttpResponse.BodyHandlers.ofString())
                .thenAccept {
                    if (it.statusCode() == 200) {
                        val json = Gson().fromJson(it.body(), JsonElement::class.java).asJsonObject
                        sendUnfurlRequest(EvalOutput(json), link, event, client)
                    }
                }
    }

    private fun sendUnfurlRequest(evalOutput: EvalOutput, link: LinkSharedEvent.Link, event: LinkSharedEvent, client: HttpClient) {
        fun renderMessage(): JsonObject {
            val blocksHolder = JsonObject()
            blocksHolder.add("blocks", createBlocks(evalOutput))
            val renderedMessage = JsonObject()
            renderedMessage.add(link.url, blocksHolder)
            return renderedMessage
        }
        fun buildRequest(): String {
            val r = JsonObject()
            val renderedMessage = renderMessage()
            r.add("unfurls", renderedMessage)
            r.addProperty("channel", event.channel)
            r.addProperty("ts", event.messageTs)
            return r.toString()
        }

        val postRequest = HttpRequest.newBuilder(unfurl)
                .header("Content-Type", applicationJsonHeader)
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequest()))
                .build()
        client.sendAsync(postRequest, HttpResponse.BodyHandlers.ofString())
                .thenAccept { it.body() }
    }

    private fun createGetEvalOutputJsonRequest(link: LinkSharedEvent.Link): HttpRequest? {
        return HttpRequest
                .newBuilder(URI.create(link.url.keepOnlyMainUrl()))
                .header("Accept", applicationJsonHeader)
                .GET()
                .build()
    }
}

private fun String.keepOnlyMainUrl(): String {
    val evalPart = "3v4l.org/"
    val evalPartIndex = indexOf(evalPart)
    if (evalPartIndex < 0) return this
    val nextSlash = indexOf("/", evalPartIndex + evalPart.length)
    return if (nextSlash < 0) this else substring(0, nextSlash)
}

class EvalOutput(private val outputJson: JsonObject) {
    val code: String
        get() {
            return outputJson.getAsJsonObject("script").get("code").asString
        }

    val runResults: Collection<EvalRunResult>
        get() {
            return outputJson.getAsJsonArray("output")
                    .map { EvalRunResult(it.asJsonObject.get("versions").asString, it.asJsonObject.get("output").asString) }
        }
}

data class EvalRunResult(val versions: String, val output: String)

fun String.codeBlock(): String {
    return if (isEmpty()) "" else "```\n" +
            "${this.trim()}\n" +
            "```";
}
