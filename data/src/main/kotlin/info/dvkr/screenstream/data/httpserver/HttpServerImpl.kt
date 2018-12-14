package info.dvkr.screenstream.data.httpserver

import android.content.Context
import androidx.annotation.Keep
import info.dvkr.screenstream.data.R
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.other.randomString
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import timber.log.Timber
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class HttpServerImpl constructor(
    context: Context,
    private val jpegChannel: ReceiveChannel<ByteArray>,
    private val onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    onError: (AppError) -> Unit
) : HttpServerCoroutineScope(onError), HttpServer {

    private companion object {
        private const val NETTY_IO_THREADS_NUMBER = 2

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()
            RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
                .apply { globalServerParentEventLoop().shutdownGracefully() }
        }
    }

    private enum class State {
        CREATED, INIT, CONFIGURED, RUNNING, ERROR
    }

    @Suppress("ArrayInDataClass")
    @Keep private data class ServerState(
        val state: State = State.CREATED,
        val favicon: ByteArray = ByteArray(0),
        val logo: ByteArray = ByteArray(0),
        val baseIndexHtml: String = "",
        val basePinRequestHtml: String = "",
        val currentIndexHtml: String = "",
        val currentStreamAddress: String = "",
        val currentPinAddress: String = "",
        val currentPinRequestHtmlPage: String = "",
        val currentPinRequestErrorHtmlPage: String = "",
        val pinEnabled: Boolean = false,
        val globalServerEventLoop: EventLoopGroup? = null,
        val nettyHttpServer: io.reactivex.netty.protocol.http.server.HttpServer<ByteBuf, ByteBuf>? = null,
        val httpServerStatistic: HttpServerStatistic? = null,
        val httpServerRxHandler: HttpServerRxHandler? = null
    ) {
        override fun toString(): String = "ServerState: $state"

        internal fun requireState(vararg requireStates: State) {
            state in requireStates ||
                    throw IllegalStateException("HttpServer in state [$state] expected ${requireStates.contentToString()}")
        }
    }

    @Keep sealed class ServerEvent {
        @Keep object Init : ServerEvent()
        @Keep data class Configure(val settingsReadOnly: SettingsReadOnly) : ServerEvent()
        @Keep data class Start(val serverAddress: InetSocketAddress) : ServerEvent()
        @Keep object Stop : ServerEvent()
        @Keep data class ServerError(val appError: AppError) : ServerEvent()
        @Keep object Destroy : ServerEvent()

        override fun toString(): String = this::class.java.simpleName
    }

    override fun configure(settingsReadOnly: SettingsReadOnly) =
        sendServerEvent(ServerEvent.Configure(settingsReadOnly))

    override fun start(serverAddress: InetSocketAddress) = sendServerEvent(ServerEvent.Start(serverAddress))

    override fun stop() = sendServerEvent(ServerEvent.Stop)

    override fun destroy() = sendServerEvent(ServerEvent.Destroy)

    private val applicationContext: Context = context.applicationContext

    private val serverEventChannel: SendChannel<ServerEvent> = actor(capacity = 16) {
        var serverState = ServerState()

        for (event in this@actor) try {
            Timber.tag(this@HttpServerImpl.getTag("actor")).v("$serverState. Request: $event")

            when (event) {
                is ServerEvent.Init -> serverState = initServer(serverState)
                is ServerEvent.Configure -> serverState = configureServer(serverState, event.settingsReadOnly)
                is ServerEvent.Start -> serverState = startServer(serverState, event.serverAddress)
                is ServerEvent.Stop -> serverState = stopServer(serverState)
                is ServerEvent.ServerError -> serverState = serverError(serverState, event.appError)
                is ServerEvent.Destroy -> super.destroy()
            }
        } catch (throwable: Throwable) {
            Timber.tag(this@HttpServerImpl.getTag("actor")).e(throwable)
            onError(FatalError.ActorException)
        }
    }

    private fun sendServerEvent(event: ServerEvent) {
        parentJob.isActive || return

        if (serverEventChannel.isClosedForSend) {
            Timber.tag(getTag("sendServerEvent")).e(IllegalStateException("Channel is ClosedForSend"))
            onError(FatalError.ChannelException)
        } else if (serverEventChannel.offer(event).not()) {
            Timber.tag(getTag("sendServerEvent")).e(IllegalStateException("Channel is full"))
            onError(FatalError.ChannelException)
        }
    }

    init {
        Timber.tag(getTag("Init")).d("Invoked")
        sendServerEvent(ServerEvent.Init)
    }

    private fun initServer(serverState: ServerState): ServerState {
        Timber.tag(getTag("initServer")).d("Invoked")
        serverState.requireState(State.CREATED)

        val favicon = getFileFromAssets(applicationContext, HttpServerFiles.FAVICON_ICO)
        val logo = getFileFromAssets(applicationContext, HttpServerFiles.LOGO_PNG)

        val indexHtmlBytes = getFileFromAssets(applicationContext, HttpServerFiles.INDEX_HTML)
        val baseIndexHtml = String(indexHtmlBytes, StandardCharsets.UTF_8)
            .replaceFirst(
                HttpServerFiles.INDEX_HTML_NO_MJPEG_SUPPORT_MESSAGE.toRegex(),
                applicationContext.getString(R.string.html_no_mjpeg_support)
            )

        val pinRequestHtmlBytes = getFileFromAssets(applicationContext, HttpServerFiles.PINREQUEST_HTML)
        val basePinRequestHtml = String(pinRequestHtmlBytes, StandardCharsets.UTF_8)
            .replaceFirst(
                HttpServerFiles.PINREQUEST_HTML_STREAM_REQUIRE_PIN.toRegex(),
                applicationContext.getString(R.string.html_stream_require_pin)
            )
            .replaceFirst(
                HttpServerFiles.PINREQUEST_HTML_ENTER_PIN.toRegex(),
                applicationContext.getString(R.string.html_enter_pin)
            )
            .replaceFirst(
                HttpServerFiles.PINREQUEST_HTML_FOUR_DIGITS.toRegex(),
                applicationContext.getString(R.string.html_four_digits)
            )
            .replaceFirst(
                HttpServerFiles.PINREQUEST_HTML_SUBMIT_TEXT.toRegex(),
                applicationContext.getString(R.string.html_submit_text)
            )

        return serverState.copy(
            state = State.INIT,
            favicon = favicon,
            logo = logo,
            baseIndexHtml = baseIndexHtml,
            basePinRequestHtml = basePinRequestHtml
        )
    }

    private fun configureServer(serverState: ServerState, settingsReadOnly: SettingsReadOnly): ServerState {
        Timber.tag(getTag("configureServer")).d("Invoked")
        serverState.requireState(State.INIT, State.CONFIGURED, State.ERROR)

        var currentIndexHtml = serverState.baseIndexHtml.replaceFirst(
            HttpServerFiles.INDEX_HTML_BACKGROUND_COLOR.toRegex(),
            "#%06X".format(0xFFFFFF and settingsReadOnly.htmlBackColor)
        )

        if (settingsReadOnly.disableMJPEGCheck) {
            currentIndexHtml = currentIndexHtml
                .replaceFirst("id=mj".toRegex(), "")
                .replaceFirst("id=pmj".toRegex(), "")
        }

        val currentStreamAddress: String
        val currentPinAddress: String
        val currentPinRequestHtmlPage: String
        val currentPinRequestErrorHtmlPage: String

        if (settingsReadOnly.enablePin) {
            currentStreamAddress = "/" + randomString(16) + ".mjpeg"

            currentPinAddress = HttpServerFiles.DEFAULT_PIN_ADDRESS + settingsReadOnly.pin

            currentPinRequestHtmlPage = serverState.basePinRequestHtml.replaceFirst(
                HttpServerFiles.PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(),
                "&nbsp"
            )

            currentPinRequestErrorHtmlPage = serverState.basePinRequestHtml.replaceFirst(
                HttpServerFiles.PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(),
                applicationContext.getString(R.string.html_wrong_pin)
            )
        } else {
            currentStreamAddress = HttpServerFiles.DEFAULT_STREAM_ADDRESS
            currentPinAddress = HttpServerFiles.DEFAULT_PIN_ADDRESS
            currentPinRequestHtmlPage = ""
            currentPinRequestErrorHtmlPage = ""
        }

        currentIndexHtml = currentIndexHtml.replaceFirst(
            HttpServerFiles.INDEX_HTML_SCREEN_STREAM_ADDRESS.toRegex(),
            currentStreamAddress
        )

        return serverState.copy(
            state = State.CONFIGURED,
            currentIndexHtml = currentIndexHtml,
            currentStreamAddress = currentStreamAddress,
            currentPinAddress = currentPinAddress,
            currentPinRequestHtmlPage = currentPinRequestHtmlPage,
            currentPinRequestErrorHtmlPage = currentPinRequestErrorHtmlPage,
            pinEnabled = settingsReadOnly.enablePin
        )
    }

    private fun startServer(serverState: ServerState, serverAddress: InetSocketAddress): ServerState {
        Timber.tag(getTag("startServer")).d("Invoked")
        serverState.requireState(State.CONFIGURED)

        if (serverAddress.port !in 1025..65535) throw IllegalArgumentException("Tcp port must be in range [1025, 65535]")

        val globalServerEventLoop = RxNetty.getRxEventLoopProvider().globalServerEventLoop()

        val nettyHttpServer = io.reactivex.netty.protocol.http.server.HttpServer.newServer(
            serverAddress, globalServerEventLoop, NioServerSocketChannel::class.java
        )
            .clientChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
//          .enableWireLogging(LogLevel.ERROR)

        val httpServerStatistic = HttpServerStatistic(::onStatistic.get()) { appError ->
            sendServerEvent(ServerEvent.ServerError(appError))
        }

        val httpServerRxHandler = HttpServerRxHandler(
            serverState.favicon,
            serverState.logo,
            serverState.currentIndexHtml,
            serverState.currentStreamAddress,
            serverState.pinEnabled,
            serverState.currentPinAddress,
            serverState.currentPinRequestHtmlPage,
            serverState.currentPinRequestErrorHtmlPage,
            { statisticEvent -> httpServerStatistic.sendStatisticEvent(statisticEvent) },
            jpegChannel,
            { appError -> sendServerEvent(ServerEvent.ServerError(appError)) }
        )

        var exception: AppError? = null
        try {
            nettyHttpServer.start(httpServerRxHandler)
        } catch (ex: BindException) {
            Timber.tag(getTag("startServer")).e(ex)
            exception = FixableError.AddressInUseException
            sendServerEvent(ServerEvent.ServerError(exception))
        } catch (throwable: Throwable) {
            Timber.tag(getTag("startServer")).e(throwable)
            exception = FatalError.NettyServerException
            sendServerEvent(ServerEvent.ServerError(exception))
        }

        return serverState.copy(
            state = if (exception == null) State.RUNNING else State.ERROR,
            globalServerEventLoop = globalServerEventLoop,
            nettyHttpServer = nettyHttpServer,
            httpServerStatistic = httpServerStatistic,
            httpServerRxHandler = httpServerRxHandler
        )
    }

    private fun stopServer(serverState: ServerState): ServerState {
        Timber.tag(getTag("stopServer")).d("Invoked")

        serverState.httpServerRxHandler?.destroy()
        serverState.httpServerStatistic?.destroy()

        try {
            serverState.nettyHttpServer?.shutdown()
            serverState.nettyHttpServer?.awaitShutdown()
        } catch (throwable: Throwable) {
            Timber.tag(getTag("stopServer")).w(throwable)
        }

        serverState.globalServerEventLoop?.shutdownGracefully()
        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))

        return serverState.copy(
            state = State.INIT,
            globalServerEventLoop = null,
            nettyHttpServer = null,
            httpServerStatistic = null,
            httpServerRxHandler = null
        )
    }

    private fun serverError(serverState: ServerState, appError: AppError): ServerState {
        Timber.tag(getTag("serverError")).d("Invoked")
        onError(appError)
        return serverState.copy(state = State.ERROR)
    }

    private fun getFileFromAssets(applicationContext: Context, fileName: String): ByteArray {
        Timber.tag(getTag("getFileFromAssets")).d(fileName)

        applicationContext.assets.open(fileName).use { inputStream ->
            val fileBytes = ByteArray(inputStream.available())
            inputStream.read(fileBytes)
            fileBytes.isNotEmpty() || throw IllegalStateException("$fileName is empty")
            return fileBytes
        }
    }
}