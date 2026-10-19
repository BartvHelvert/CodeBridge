package me.helvert.codebridge.editors

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.project.projectId
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.exthost.protocol.payload.Urls
import me.helvert.codebridge.rpc.AppLinkOutcomeDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal class CodeBridgeAppLinksActivity : ProjectActivity {
    override suspend fun execute(project: Project) = service<CodeBridgeAppLinkServer>().start()
}

/**
 * The links into the editor extensions hand to a browser -- a sign-in's callback -- served on this
 * client's built-in web server, since the browser is on this client's machine.
 *
 * The backend gives links out on this server's port for as long as this client keeps telling it,
 * and delivers what the browser opens here to the extension the link names.
 */
@Service(Service.Level.APP)
internal class CodeBridgeAppLinkServer(private val scope: CoroutineScope) {

    private val started = AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val port = withContext(Dispatchers.IO) { BuiltInServerManager.getInstance().waitForStart().port }
            // The editor's own scheme, once this machine hands it to the launcher; asked again after a reconnect.
            var handled: String? = null
            // Reconnects like the other streams: a backend restart ends it, and links must still work after.
            while (isActive) {
                try {
                    val api = CodeBridgeApi.getInstance()
                    val scheme = api.appUriScheme()
                    if (scheme != null && scheme != handled && withContext(Dispatchers.IO) {
                            CodeBridgeSchemeHandler.register(
                                scheme,
                                port
                            )
                        }) {
                        handled = scheme
                    }
                    api.appLinkPort(port, listOfNotNull(handled?.takeIf { it == scheme })).collect {}
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.debug("Lost the backend while serving links into the editor", failure)
                }
                delay(2.seconds)
            }
        }
    }

    /** Has the backend deliver [requestUri], then answers [context] with how that went. */
    fun open(requestUri: String, context: ChannelHandlerContext) {
        val focusedProject = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        val focused = focusedProject?.projectId()
        scope.launch {
            // Ahead of the answer: the browser is in front, and the user may be asked first.
            if (focusedProject != null) withContext(ANY_MODALITY) {
                if (!focusedProject.isDisposed) ProjectUtil.focusProjectWindow(focusedProject, true)
            }
            val outcome = try {
                CodeBridgeApi.getInstance().openAppLink(requestUri, focused).first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not deliver a link into the editor", failure)
                AppLinkOutcomeDto(problem = "The IDE backend could not be reached.")
            }
            respond(context, outcome)
            // What the user signed in for is in that window.
            val project = outcome.projectId?.findProjectOrNull()
            if (outcome.extension != null && project != null) withContext(ANY_MODALITY) {
                ProjectUtil.focusProjectWindow(
                    project,
                    true
                )
            }
        }
    }

    private fun respond(context: ChannelHandlerContext, outcome: AppLinkOutcomeDto) {
        val product = ApplicationNamesInfo.getInstance().fullProductName
        val (status, title, text) = when {
            outcome.extension != null -> Triple(
                HttpResponseStatus.OK, "Sent to ${outcome.extension}",
                "You can close this tab and return to $product."
            )

            outcome.declined -> Triple(HttpResponseStatus.FORBIDDEN, "Not opened", "The link was not allowed to open.")
            else -> Triple(
                HttpResponseStatus.NOT_FOUND, "Nothing to open this link",
                outcome.problem ?: "No extension in $product took it."
            )
        }
        respond(context, status, title, text)
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeAppLinkServer::class.java)

        /**
         * The editor thread whatever dialog is open: a sign-in waits for its link behind a modal box
         * for pasting the code by hand, and only the link arriving closes that box.
         */
        private val ANY_MODALITY = Dispatchers.EDT + ModalityState.any().asContextElement()

        /** The request is released once the handler returns, so nothing of it is read here. */
        fun respond(context: ChannelHandlerContext, status: HttpResponseStatus, title: String, text: String) {
            val html =
                """<!doctype html><html><head><meta charset="utf-8"><title>${StringUtil.escapeXmlEntities(title)}</title>
                |<style>body{font:15px system-ui,sans-serif;margin:15vh auto;max-width:32em;padding:0 16px;color:#222}
                |@media (prefers-color-scheme:dark){body{background:#1e1f22;color:#dfe1e5}}</style></head>
                |<body><h1>${StringUtil.escapeXmlEntities(title)}</h1><p>${StringUtil.escapeXmlEntities(text)}</p></body></html>""".trimMargin()
            val response =
                DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(html, Charsets.UTF_8))
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
            HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
            context.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
    }
}

/** A link [CodeBridgeAppLinkServer] serves, opened in the browser. */
internal class CodeBridgeAppLinks : HttpRequestHandler() {

    override fun isSupported(request: FullHttpRequest): Boolean =
        super.isSupported(request) && checkPrefix(request.uri(), Urls.LINK_PREFIX)

    /**
     * A sign-in provider's page redirects here, so the request may carry that page's origin. The
     * `Host` must still be this machine, which [isAccessible] checks; the link's token, or the user,
     * does the rest.
     */
    override fun isOriginAllowed(request: HttpRequest): OriginCheckResult = OriginCheckResult.ALLOW

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        service<CodeBridgeAppLinkServer>().open(request.uri(), context)
        return true
    }
}
