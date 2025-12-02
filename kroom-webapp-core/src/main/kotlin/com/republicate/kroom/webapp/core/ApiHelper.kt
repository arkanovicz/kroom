package com.republicate.kroom.webapp.core

import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * API response helpers for consistent JSON responses.
 */

suspend fun RoutingContext.respondJson(json: Json) {
    call.respondText(json.toString(), ContentType.Application.Json)
}

suspend fun RoutingContext.respondJson(block: Json.MutableObject.() -> Unit) {
    val json = Json.MutableObject().apply(block)
    respondJson(json)
}

suspend fun RoutingContext.respondSuccess(message: String? = null) {
    respondJson {
        set("success", true)
        message?.let { set("message", it) }
    }
}

suspend fun RoutingContext.respondError(message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) {
    call.respondText(
        Json.MutableObject().apply {
            set("success", false)
            set("message", message)
        }.toString(),
        ContentType.Application.Json,
        status
    )
}

/**
 * Parse JSON body from request.
 */
suspend fun RoutingContext.receiveJson(): Json {
    val text = call.receiveText()
    return Json.parse(text) ?: throw IllegalArgumentException("Invalid JSON")
}

suspend fun RoutingContext.receiveJsonObject(): Json.Object {
    val json = receiveJson()
    return json as? Json.Object ?: throw IllegalArgumentException("Expected JSON object")
}
