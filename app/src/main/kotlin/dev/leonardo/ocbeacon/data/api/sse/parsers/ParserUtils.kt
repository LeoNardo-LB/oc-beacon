package dev.leonardo.ocbeacon.data.api.sse.parsers

import kotlinx.serialization.json.*

internal fun JsonObject.str(key: String, default: String = ""): String {
    val element = this[key] ?: return default
    return when {
        element === JsonNull -> default
        element is JsonPrimitive -> element.content
        element is JsonObject -> (element["message"] as? JsonPrimitive)?.content
            ?: (element["data"] as? JsonObject)?.let { (it["message"] as? JsonPrimitive)?.content }
            ?: (element["error"] as? JsonPrimitive)?.content
            ?: (element["type"] as? JsonPrimitive)?.content
            ?: (element["name"] as? JsonPrimitive)?.content
            ?: default
        element is JsonArray -> default
        else -> default
    }
}
