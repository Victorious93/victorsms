package com.victorious.sms.bridge

import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Structural equality of two JSON documents, ignoring key order. (Android's org.json stub has no `similar`.) */
internal fun sameTree(expected: String, actual: JSONObject): Boolean =
    Json.parseToJsonElement(expected) == Json.parseToJsonElement(actual.toString())
