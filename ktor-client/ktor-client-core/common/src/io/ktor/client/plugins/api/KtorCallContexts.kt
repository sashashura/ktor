/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package io.ktor.client.plugins.api

import io.ktor.util.*

/**
 * A context for [ClientPluginBuilder.onRequest] callback.
 */
@KtorDsl
public class OnRequestContext internal constructor()

/**
 * A context for [ClientPluginBuilder.onResponse] callback.
 */
@KtorDsl
public class OnResponseContext internal constructor()

/**
 * A context for [ClientPluginBuilder.transformRequestBody] callback.
 */
@KtorDsl
public class TransformRequestBodyContext internal constructor()

/**
 * A context for [ClientPluginBuilder.transformResponseBody] callback.
 */
@KtorDsl
public class TransformResponseBodyContext internal constructor()
