package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.model.ingest.RawMessage
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The only place that talks to Jackson (3.x, `tools.jackson`). The rest of the ingest code sees
 * plain Kotlin values, so a change of JSON library or major version stays in this file.
 */
class TelemetryJson(private val mapper: JsonMapper = JsonMapper()) {

    /** Returns `null` when [text] is not valid JSON or its root is not an object. */
    fun parse(text: String): RawMessage? {
        val root = try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            return null
        }
        if (root !is ObjectNode) return null
        return RawMessage(
            type = root.stringOrNull("type"),
            deviceId = root.stringOrNull("deviceId"),
            timestamp = root.stringOrNull("timestamp"),
            stats = (root.get("stats") as? ObjectNode)?.properties()?.associate { (key, value) ->
                key to value.takeIf(JsonNode::isNumber)?.doubleValue()
            },
        )
    }

    fun errorFrame(reason: String): String =
        mapper.writeValueAsString(
            JsonNodeFactory.instance.objectNode().put("type", "error").put("reason", reason),
        )

    private fun ObjectNode.stringOrNull(field: String): String? = get(field)?.takeIf(JsonNode::isString)?.stringValue()
}
