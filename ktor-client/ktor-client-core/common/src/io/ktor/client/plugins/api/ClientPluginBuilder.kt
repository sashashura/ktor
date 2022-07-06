/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.plugins.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.events.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*

/**
 * A utility class to build an [ClientPlugin] instance.
 **/
@KtorDsl
public class ClientPluginBuilder<PluginConfig : Any> internal constructor(
    internal val key: AttributeKey<ClientPluginInstance<PluginConfig>>,
    /**
     * A reference to the [HttpClient] where the plugin is installed.
     **/
    public val client: HttpClient,
    /**
     * A configuration of the current plugin.
     */
    public val pluginConfig: PluginConfig
) {

    internal val hooks: MutableList<HookHandler<*>> = mutableListOf()
    internal var onClose: () -> Unit = {}

    /**
     * Specifies the [block] handler for every http request.
     *
     * This block is invoked for every [HttpClient.request] call.
     * There you can modify the request in a way you want: add headers, logging, etc.
     *
     * @see [createClientPlugin]
     *
     * @param block An action that needs to be executed when a client creates http request.
     */
    public fun onRequest(
        block: suspend OnRequestContext.(request: HttpRequestBuilder, content: Any) -> Unit
    ) {
        on(RequestHook, block)
    }

    /**
     * Specifies the [block] handler for every http response.
     *
     * This block is invoked for every incoming response.
     * There you can inspect the response in a way you want: save cookies, add logging, etc.
     *
     * @see [createClientPlugin]
     *
     * @param block An action that needs to be executed when a client receives http response.
     */
    public fun onResponse(
        block: suspend OnResponseContext.(response: HttpResponse) -> Unit
    ) {
        on(ResponseHook, block)
    }

    /**
     * Specifies the [block] transformer for request body.
     *
     * This block is invoked for every [HttpClient.request] call.
     * There you should serialize body into [OutgoingContent] or return `null` if your transformation is not applicable.
     *
     * @see [createClientPlugin]
     *
     * @param block A transformation of request body.
     */
    public fun transformRequestBody(
        block: suspend TransformRequestBodyContext.(
            request: HttpRequestBuilder,
            content: Any,
            bodyType: TypeInfo?
        ) -> OutgoingContent?
    ) {
        on(TransformRequestBodyHook, block)
    }

    /**
     * Specifies the [block] transformer for response body.
     *
     * This block is invoked for every [HttpResponse.body] call.
     * There you should deserialize body into an instance of [requestedType]
     * or return `null` if your transformation is not applicable.
     *
     * @see [createClientPlugin]
     *
     * @param block A transformation of response body.
     */
    public fun transformResponseBody(
        block: suspend TransformResponseBodyContext.(
            response: HttpResponse,
            content: ByteReadChannel,
            requestedType: TypeInfo
        ) -> Any?
    ) {
        on(TransformResponseBodyHook, block)
    }

    /**
     * Specifies the [block] to clean resources allocated with this plugin.
     */
    public fun onClose(block: () -> Unit) {
        onClose = block
    }

    /**
     * Specifies a [handler] for a specific [hook].
     * A [hook] can be a specific place in time or event during the request
     * processing like receiving response, an exception during call processing, etc.
     *
     * @see [createClientPlugin]
     */
    public fun <HookHandler> on(
        hook: ClientHook<HookHandler>,
        handler: HookHandler
    ) {
        hooks.add(HookHandler(hook, handler))
    }
}

private object RequestHook :
    ClientHook<suspend OnRequestContext.(request: HttpRequestBuilder, content: Any) -> Unit> {

    override fun install(
        client: HttpClient,
        handler: suspend OnRequestContext.(request: HttpRequestBuilder, content: Any) -> Unit
    ) {
        client.requestPipeline.intercept(HttpRequestPipeline.State) {
            handler(OnRequestContext(), context, subject)
        }
    }
}

private object ResponseHook :
    ClientHook<suspend OnResponseContext.(response: HttpResponse) -> Unit> {

    override fun install(
        client: HttpClient,
        handler: suspend OnResponseContext.(response: HttpResponse) -> Unit
    ) {
        client.receivePipeline.intercept(HttpReceivePipeline.State) {
            handler(OnResponseContext(), subject)
        }
    }
}

private object TransformRequestBodyHook :
    ClientHook<suspend TransformRequestBodyContext.(
        request: HttpRequestBuilder,
        content: Any,
        bodyType: TypeInfo?
    ) -> OutgoingContent?> {

    override fun install(
        client: HttpClient,
        handler: suspend TransformRequestBodyContext.(
            request: HttpRequestBuilder,
            content: Any,
            bodyType: TypeInfo?
        ) -> OutgoingContent?
    ) {
        client.requestPipeline.intercept(HttpRequestPipeline.Transform) {
            val newContent = handler(TransformRequestBodyContext(), context, subject, context.bodyType)
            if (newContent != null) proceedWith(newContent)
        }
    }
}

private object TransformResponseBodyHook :
    ClientHook<suspend TransformResponseBodyContext.(
        response: HttpResponse,
        content: ByteReadChannel,
        requestedType: TypeInfo
    ) -> Any?> {

    override fun install(
        client: HttpClient,
        handler: suspend TransformResponseBodyContext.(
            response: HttpResponse,
            content: ByteReadChannel,
            requestedType: TypeInfo
        ) -> Any?
    ) {
        client.responsePipeline.intercept(HttpResponsePipeline.Transform) {
            val (typeInfo, content) = subject
            if (content !is ByteReadChannel) return@intercept
            val newContent = handler(TransformResponseBodyContext(), context.response, content, typeInfo)
                ?: return@intercept
            if (newContent !is NullBody && !typeInfo.type.isInstance(newContent)) {
                throw IllegalStateException(
                    "transformResponseBody returned $newContent but expected value of type $typeInfo"
                )
            }
            proceedWith(HttpResponseContainer(typeInfo, newContent))
        }
    }
}

/**
 * A hook that executes first in request processing.
 */
public object SetupRequest : ClientHook<suspend (HttpRequestBuilder) -> Unit> {
    override fun install(client: HttpClient, handler: suspend (HttpRequestBuilder) -> Unit) {
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            handler(context)
        }
    }
}

/**
 * A hook that can inspect response and initiate additional requests if needed.
 * Useful for handling redirects, retries, authentication, etc.
 */
public object Send : ClientHook<suspend Send.Sender.(HttpRequestBuilder) -> HttpClientCall> {

    public class Sender internal constructor(private val httpSendSender: io.ktor.client.plugins.Sender) {
        public suspend fun proceed(requestBuilder: HttpRequestBuilder): HttpClientCall =
            httpSendSender.execute(requestBuilder)
    }

    override fun install(client: HttpClient, handler: suspend Sender.(HttpRequestBuilder) -> HttpClientCall) {
        client.plugin(HttpSend).intercept { request ->
            handler(Sender(this), request)
        }
    }
}

/**
 * A hook that is executed for every request, even if it's not user initiated.
 * For example, if requests results in redirect,
 * [ClientPluginBuilder.onRequest] will be executed only for original, requests,
 * but this hook will be executed for both original and redirected requests.
 */
public object SendingRequest :
    ClientHook<suspend (request: HttpRequestBuilder, content: OutgoingContent) -> Unit> {

    override fun install(
        client: HttpClient,
        handler: suspend (request: HttpRequestBuilder, content: OutgoingContent) -> Unit
    ) {
        client.sendPipeline.intercept(HttpSendPipeline.State) {
            handler(context, subject as OutgoingContent)
        }
    }
}

/**
 * A shortcut hook for [HttpClient.monitor] subscription.
 */
public class MonitoringEvent<Param : Any, Event : EventDefinition<Param>>(private val event: Event) :
    ClientHook<(Param) -> Unit> {

    override fun install(client: HttpClient, handler: (Param) -> Unit) {
        client.monitor.subscribe(event) {
            handler(it)
        }
    }
}
