package com.flowforge.android.workflow.model

data class FieldSpec(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val default: String = "",
    val multiline: Boolean = false,
    val hint: String = "",
)

data class NodeSpec(
    val inputs: List<String>,
    val outputs: List<String>,
    val fields: List<FieldSpec>,
)

object NodeSpecs {
    private fun f(
        key: String, label: String, required: Boolean = false, default: String = "",
        multiline: Boolean = false, hint: String = "",
    ) = FieldSpec(key, label, required, default, multiline, hint)

    private val IN = listOf("in")
    private val SUCCESS_ERROR = listOf("success", "error")

    fun of(type: NodeType): NodeSpec = when (type) {
        NodeType.START -> NodeSpec(emptyList(), listOf("out"), emptyList())
        NodeType.END -> NodeSpec(IN, emptyList(), emptyList())
        NodeType.STOP -> NodeSpec(IN, emptyList(), emptyList())
        NodeType.HTTP_REQUEST -> NodeSpec(
            IN, SUCCESS_ERROR,
            listOf(
                f("method", "Method", required = true, default = "GET", hint = "GET POST PUT PATCH DELETE HEAD OPTIONS"),
                f("url", "URL", required = true, hint = "https://host/path (host must be in the allowlist)"),
                f("headers", "Headers", multiline = true, hint = "Name: Value, one per line"),
                f("query", "Query", multiline = true, hint = "key=value, one per line"),
                f("bodyType", "Body type", required = true, default = "NONE", hint = "NONE JSON FORM URL_ENCODED RAW"),
                f("body", "Body", multiline = true, hint = "JSON/raw text, or key=value lines for forms"),
                f("followRedirects", "Follow redirects", required = true, default = "true"),
                f("saveAs", "Save response as", required = true, default = "resp", hint = "Creates resp.status, resp.body, ..."),
            ),
        )
        NodeType.SET_VARIABLE -> NodeSpec(
            IN, listOf("out"),
            listOf(f("name", "Variable name", required = true), f("value", "Value", hint = "Supports \${variable}")),
        )
        NodeType.CONDITION, NodeType.IF_ELSE -> NodeSpec(
            IN, listOf("true", "false"),
            listOf(
                f("left", "Left", hint = "Supports \${variable}"),
                f("op", "Operator", required = true, default = "eq",
                    hint = "eq neq contains starts_with ends_with gt lt regex empty not_empty"),
                f("right", "Right"),
            ),
        )
        NodeType.DELAY -> NodeSpec(IN, listOf("out"), listOf(f("ms", "Delay (ms)", required = true, default = "500", hint = "0..60000")))
        NodeType.LOGGER -> NodeSpec(IN, listOf("out"), listOf(f("message", "Message", hint = "Supports \${variable}; secrets are redacted")))
        NodeType.JSON_PARSER -> NodeSpec(
            IN, SUCCESS_ERROR,
            listOf(
                f("source", "Source variable", hint = "Empty = last HTTP response body"),
                f("path", "JSONPath", required = true, hint = "\$.data.items[0].id"),
                f("saveAs", "Save as", required = true, default = "parsed"),
            ),
        )
        NodeType.HTML_PARSER -> NodeSpec(
            IN, SUCCESS_ERROR,
            listOf(
                f("source", "Source variable", hint = "Empty = last HTTP response body"),
                f("selector", "CSS selector", required = true),
                f("attr", "Attribute", hint = "Empty = element text"),
                f("saveAs", "Save as", required = true, default = "parsed"),
            ),
        )
        NodeType.REGEX_PARSER -> NodeSpec(
            IN, SUCCESS_ERROR,
            listOf(
                f("source", "Source variable", hint = "Empty = last HTTP response body"),
                f("pattern", "Pattern", required = true),
                f("group", "Group", hint = "Empty = group 1 if present, else whole match"),
                f("saveAs", "Save as", required = true, default = "parsed"),
            ),
        )
        NodeType.HEADER_PARSER -> NodeSpec(
            IN, SUCCESS_ERROR,
            listOf(f("name", "Header name", required = true), f("saveAs", "Save as", required = true, default = "parsed")),
        )
        NodeType.TEXT_EXTRACTOR -> NodeSpec(
            IN, SUCCESS_ERROR,
            listOf(
                f("source", "Source variable", hint = "Empty = last HTTP response body"),
                f("left", "Left marker", required = true),
                f("right", "Right marker", required = true),
                f("saveAs", "Save as", required = true, default = "parsed"),
            ),
        )
        NodeType.INPUT_DATASET -> NodeSpec(
            IN, listOf("row", "end"),
            listOf(f("name", "Dataset name", required = true), f("saveAs", "Row variable", required = true, default = "row")),
        )
        NodeType.DATA_TRANSFORM -> NodeSpec(
            IN, listOf("out"),
            listOf(
                f("source", "Source variable", required = true),
                f("op", "Operation", required = true, default = "trim",
                    hint = "trim lowercase uppercase length urlencode urldecode base64_encode base64_decode"),
                f("target", "Target variable", required = true),
            ),
        )
        NodeType.DATA_VALIDATION -> NodeSpec(
            IN, listOf("valid", "invalid"),
            listOf(
                f("source", "Source variable", required = true),
                f("rule", "Rule", required = true, default = "not_empty", hint = "not_empty regex number min_length max_length"),
                f("arg", "Argument"),
            ),
        )
        NodeType.LOOP -> NodeSpec(
            IN, listOf("body", "done"),
            listOf(
                f("count", "Iterations", required = true, default = "3", hint = "Capped by the workflow loop limit"),
                f("indexVar", "Index variable", required = true, default = "loop.index"),
            ),
        )
        NodeType.SUCCESS_BRANCH, NodeType.ERROR_BRANCH -> NodeSpec(IN, listOf("match", "other"), emptyList())
    }

    fun defaultConfig(type: NodeType): Map<String, String> = of(type).fields.associate { it.key to it.default }
}
