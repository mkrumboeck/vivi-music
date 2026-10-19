/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.innertube

import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class NewPipeDownloaderImpl(
    proxy: Proxy?,
    proxyAuth: String? = null,
) : Downloader() {
    private val client =
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    return when (YouTube.ipVersion) {
                        IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                        IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                        IpVersion.AUTO -> addresses
                    }
                }
            })
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOfNotNull(YouTube.proxy ?: Proxy.NO_PROXY)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            })
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}

class NewPipeUtils(
    downloader: Downloader,
) {
    init {
        NewPipe.init(downloader)
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> =
        runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): String? =
        try {
            val url =
                format.url ?: format.signatureCipher?.let { signatureCipher ->
                    val params = parseQueryString(signatureCipher)
                    val obfuscatedSignature =
                        params["s"]
                            ?: throw ParsingException("Could not parse cipher signature")
                    val signatureParam =
                        params["sp"]
                            ?: throw ParsingException("Could not parse cipher signature parameter")
                    val url =
                        params["url"]?.let { URLBuilder(it) }
                            ?: throw ParsingException("Could not parse cipher url")
                    url.parameters[signatureParam] =
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                            videoId,
                            obfuscatedSignature,
                        )
                    url.toString()
                } ?: throw ParsingException("Could not find format url")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        } catch (e: Exception) {
            // Don't print stack trace - caller handles errors
            null
        }
}

object NewPipeExtractor {
    private var newPipeDownloader: NewPipeDownloaderImpl? = null
    private var newPipeUtils: NewPipeUtils? = null
    private var isInitialized = false

    fun init() {
        if (!isInitialized) {
            newPipeDownloader = NewPipeDownloaderImpl(
                proxy = YouTube.proxy,
                proxyAuth = YouTube.proxyAuth
            )
            newPipeUtils = NewPipeUtils(newPipeDownloader!!)
            isInitialized = true
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> {
        init()
        return newPipeUtils?.getSignatureTimestamp(videoId)
            ?: Result.failure(Exception("NewPipeUtils not initialized"))
    }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        init()
        return newPipeUtils?.getStreamUrl(format, videoId)
    }

    /** Prefers M4A for Sonos while retaining direct-stream fallbacks. */
    fun sonosAudioStream(videoId: String): Pair<String, String>? {
        init()
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(true)
        val info = StreamInfo.getInfo(NewPipe.getService(0), "https://www.youtube.com/watch?v=$videoId")
        val audio = selectSonosAudio(info.audioStreams)
        if (audio != null) {
            return audio.content to (audio.format?.mimeType ?: return null)
        }
        // Progressive MP4 can provide an audio track when no separate audio URL is available.
        val muxed = info.videoStreams
            .filter { it.isUrl && !it.isVideoOnly && it.format?.mimeType == "video/mp4" &&
                it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { if (it.itagItem?.id == 18) 1 else 0 } ?: return null
        return muxed.content to "video/mp4"
    }

    internal fun selectSonosAudio(
        streams: List<org.schabi.newpipe.extractor.stream.AudioStream>,
    ): org.schabi.newpipe.extractor.stream.AudioStream? {
        return streams
            .filter { it.isUrl && it.deliveryMethod in listOf(
                org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP,
                org.schabi.newpipe.extractor.stream.DeliveryMethod.DASH,
            ) }
            .filter { it.format?.mimeType in listOf("audio/mp4", "audio/mpeg", "audio/webm") }
            .maxByOrNull {
                (if (it.itagItem?.id == 140) 100_000 else 0) +
                    (if (it.format?.mimeType == "audio/mp4") 10_000 else 0) +
                    (if (it.format?.mimeType == "audio/mpeg") 5_000 else 0) -
                    kotlin.math.abs(it.averageBitrate - 128)
            }
    }

    fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        init()
        return try {
            val streamInfo = StreamInfo.getInfo(
                NewPipe.getService(0),
                "https://www.youtube.com/watch?v=$videoId"
            )
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }
        } catch (e: Exception) {
            // Don't print stack trace - caller handles errors
            emptyList()
        }
    }
}
