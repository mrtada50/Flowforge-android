package com.flowforge.android.workflow.model

enum class WorkflowTemplate { HTTP_CHECK, JSON_EXTRACT, DATASET_ITEM }

/** Ready-made starting points. URLs are placeholders: edit them and add the host to Allowed domains. */
object WorkflowTemplates {
    private fun node(type: NodeType, name: String, x: Float, y: Float, vararg cfg: Pair<String, String>) =
        NodeDef(newId(), type, name, x, y, NodeSpecs.defaultConfig(type) + cfg.toMap())

    private fun link(from: NodeDef, port: String, to: NodeDef) = ConnectionDef(newId(), from.id, port, to.id, "in")

    fun build(template: WorkflowTemplate, name: String): WorkflowDef = when (template) {
        WorkflowTemplate.HTTP_CHECK -> {
            val s = node(NodeType.START, "Start", 40f, 100f)
            val h = node(NodeType.HTTP_REQUEST, "GET page", 230f, 80f, "url" to "https://example.com/")
            val c = node(NodeType.CONDITION, "Status is 200?", 450f, 80f, "left" to "\${resp.status}", "op" to "eq", "right" to "200")
            val ok = node(NodeType.LOGGER, "Log OK", 680f, 20f, "message" to "OK: HTTP \${resp.status}")
            val bad = node(NodeType.LOGGER, "Log other status", 680f, 150f, "message" to "Other status: HTTP \${resp.status}")
            val err = node(NodeType.LOGGER, "Log failure", 450f, 270f, "message" to "Request failed")
            val e = node(NodeType.END, "End", 920f, 120f)
            WorkflowDef(
                id = newId(), name = name.trim(),
                nodes = listOf(s, h, c, ok, bad, err, e),
                connections = listOf(
                    link(s, "out", h), link(h, "success", c), link(h, "error", err),
                    link(c, "true", ok), link(c, "false", bad),
                    link(ok, "out", e), link(bad, "out", e), link(err, "out", e),
                ),
            )
        }
        WorkflowTemplate.JSON_EXTRACT -> {
            val s = node(NodeType.START, "Start", 40f, 100f)
            val h = node(NodeType.HTTP_REQUEST, "GET JSON", 230f, 80f, "url" to "https://example.com/api/item")
            val p = node(NodeType.JSON_PARSER, "Extract id", 450f, 80f, "path" to "\$.id", "saveAs" to "value")
            val ok = node(NodeType.LOGGER, "Log value", 680f, 60f, "message" to "Extracted: \${value}")
            val err = node(NodeType.LOGGER, "Log failure", 450f, 250f, "message" to "Request or parsing failed")
            val e = node(NodeType.END, "End", 920f, 120f)
            WorkflowDef(
                id = newId(), name = name.trim(),
                nodes = listOf(s, h, p, ok, err, e),
                connections = listOf(
                    link(s, "out", h), link(h, "success", p), link(h, "error", err),
                    link(p, "success", ok), link(p, "error", err),
                    link(ok, "out", e), link(err, "out", e),
                ),
            )
        }
        WorkflowTemplate.DATASET_ITEM -> {
            val s = node(NodeType.START, "Start", 40f, 100f)
            val h = node(
                NodeType.HTTP_REQUEST, "GET with row", 230f, 80f,
                "url" to "https://example.com/search", "query" to "q=\${row}",
            )
            val ok = node(NodeType.LOGGER, "Log item", 470f, 60f, "message" to "Item done: HTTP \${resp.status}")
            val err = node(NodeType.LOGGER, "Log failure", 470f, 220f, "message" to "Item failed")
            val e = node(NodeType.END, "End", 720f, 120f)
            WorkflowDef(
                id = newId(), name = name.trim(),
                nodes = listOf(s, h, ok, err, e),
                connections = listOf(
                    link(s, "out", h), link(h, "success", ok), link(h, "error", err),
                    link(ok, "out", e), link(err, "out", e),
                ),
            )
        }
    }
}
