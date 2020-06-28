import com.slack.api.bolt.App
import com.slack.api.bolt.jetty.SlackAppServer
import java.net.URI


fun main() {
    val config = util.ResourceLoader.loadAppConfig("config.json")
    val app = App(config)
    val handler = UnfurlOutputLinkSharedHandler(config.singleTeamBotToken)
    app.event(handler)
    SlackAppServer(app).start()

}