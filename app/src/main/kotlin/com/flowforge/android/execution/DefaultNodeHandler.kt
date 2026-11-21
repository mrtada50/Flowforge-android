package com.flowforge.android.execution

import com.flowforge.android.core.common.Redactor
import com.flowforge.android.core.common.TextParsing
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.domain.models.BodyType
import com.flowforge.android.domain.models.HttpEngineException
import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.parser.ParseResult
import com.flowforge.android.parser.ParserEngine
import com.flowforge.android.workflow.model.NodeDef
import com.flowforge.android.workflow.model.NodeType
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

private fun ok(port: String?) = NodeOutcome(NodeStatus.SUCCESS, port)
private fun fail(port: String?, msg: String, preview: String? = null, http: Int? = null) =
    NodeOutcome(NodeStatus.FAILED, port, http, msg, preview)

private fun NodeDef.cfg(key: String): String = config[key] ?: ""
private fun NodeDef.cfgOr(key: String, default: String): String = config[key]?.takeIf { it.isNotBlank() } ?: default

class DefaultNodeHandler(
    private val http: HttpClientPort,
    private val log: (String) -> Unit = {},
) : NodeHandler {

    override suspend fun execute(node: NodeDef, ctx: ExecutionContext): NodeOutcome = when (node.type) {
        NodeType.START -> ok("out")
        NodeType.END, NodeType.STOP -> ok(null)
        NodeType.HTTP_REQUEST -> httpRequest(node, ctx)
        NodeType.SET_VARIABLE -> {
            val name = node.cfg("name").trim()
            require(name.isNotEmpty()) { "Variable name is required" }
            ctx.variables[name] = ctx.resolve(node.cfg("value"))
            ok("out")
        }
        NodeType.CONDITION, NodeType.IF_ELSE -> {
            val result = evaluate(ctx.resolve(node.cfg("left")), node.cfgOr("op", "eq"), ctx.resolve(node.cfg("right")))
            ok(if (result) "true" else "false")
        }
        NodeType.DELAY -> {
            val ms = node.cfg("ms").trim().toLongOrNull() ?: throw IllegalArgumentException("Delay must be a number")
            require(ms in 0..60_000) { "Delay must be between 0 and 60000 ms" }
            delay(ms)
            ok("out")
        }
        NodeType.LOGGER -> {
            log(Redactor.redact(ctx.resolve(node.cfg("message"))))
            ok("out")
        }
        NodeType.JSON_PARSER, NodeType.HTML_PARSER, NodeType.REGEX_PARSER,
        NodeType.HEADER_PARSER, NodeType.TEXT_EXTRACTOR -> parse(node, ctx)
        NodeType.INPUT_DATASET -> {
            val provider = ctx.datasets ?: throw IllegalStateException("No dataset is available for this run")
            val row = provider.nextRow(node.cfg("name"))
            if (row == null) {
                ok("end")
            } else {
                ctx.variables[node.cfgOr("saveAs", "row")] = row
                ok("row")
            }
        }
        NodeType.DATA_TRANSFORM -> {
            val source = ctx.variables[node.cfg("source")] ?: throw UnresolvedVariableException(node.cfg("source"))
            val target = node.cfg("target").trim()
            require(target.isNotEmpty()) { "Target variable is required" }
            ctx.variables[target] = transform(source, node.cfgOr("op", "trim"))
            ok("out")
        }
        NodeType.DATA_VALIDATION -> {
            val source = ctx.variables[node.cfg("source")] ?: throw UnresolvedVariableException(node.cfg("source"))
            val rule = node.cfgOr("rule", "not_empty")
            if (validateValue(source, rule, node.cfg("arg"))) ok("valid") else fail("invalid", "Validation failed: $rule")
        }
        NodeType.LOOP -> {
            val max = ctx.settings.maxLoopIterations
            val count = node.cfg("count").trim().toIntOrNull() ?: throw IllegalArgumentException("Loop count must be a number")
            require(count in 1..max) { "Loop count must be between 1 and $max" }
            val i = ctx.loopCounters[node.id] ?: 0
            if (i < count) {
                ctx.loopCounters[node.id] = i + 1
                ctx.variables[node.cfgOr("indexVar", "loop.index")] = i.toString()
                ok("body")
            } else {
                ctx.loopCounters.remove(node.id)
                ok("done")
            }
        }
        NodeType.SUCCESS_BRANCH -> ok(if (!ctx.lastFailed) "match" else "other")
        NodeType.ERROR_BRANCH -> ok(if (ctx.lastFailed) "match" else "other")
    }

    private suspend fun httpRequest(node: NodeDef, ctx: ExecutionContext): NodeOutcome {
        val bodyType = try {
            BodyType.valueOf(node.cfgOr("bodyType", "NONE").uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown body type: ${node.cfg("bodyType")}")
        }
        val body = ctx.resolve(node.cfg("body"))
        val spec = HttpRequestSpec(
            method = node.cfgOr("method", "GET").uppercase(),
            url = ctx.resolve(node.cfg("url")).trim(),
            headers = TextParsing.headers(ctx.resolve(node.cfg("headers"))),
            query = TextParsing.pairs(ctx.resolve(node.cfg("query"))),
            bodyType = bodyType,
            body = body,
            fields = if (bodyType == BodyType.FORM || bodyType == BodyType.URL_ENCODED) TextParsing.pairs(body) else emptyList(),
            followRedirects = node.cfgOr("followRedirects", "true").toBoolean(),
        )
        val prefix = node.cfgOr("saveAs", "resp")
        val res = try {
            http.execute(spec)
        } catch (e: HttpEngineException) {
            return fail("error", "${e.kind}: ${e.message}")
        }
        ctx.lastResponse = res
        ctx.variables["$prefix.status"] = res.status.toString()
        ctx.variables["$prefix.body"] = res.body
        ctx.variables["$prefix.url"] = res.finalUrl
        ctx.variables["$prefix.duration_ms"] = res.durationMs.toString()
        ctx.variables["$prefix.headers"] = res.headers.joinToString("\n") { "${it.first}: ${it.second}" }
        res.headers.forEach { ctx.variables["$prefix.header.${it.first.lowercase()}"] = it.second }
        val preview = Redactor.redact(res.body).take(2000)
        return if (res.status in 200..399) {
            NodeOutcome(NodeStatus.SUCCESS, "success", res.status, null, preview)
        } else {
            fail("error", "HTTP ${res.status}", preview, res.status)
        }
    }

    private fun parse(node: NodeDef, ctx: ExecutionContext): NodeOutcome {
        val source = node.cfg("source").trim()
        val body: String? = if (source.isEmpty()) ctx.lastResponse?.body else ctx.variables[source]
        val result: ParseResult = when (node.type) {
            NodeType.HEADER_PARSER -> {
                val headers = ctx.lastResponse?.headers ?: return fail("error", "No HTTP response to read headers from")
                ParserEngine.header(headers, node.cfg("name"))
            }
            else -> {
                val text = body ?: return fail("error", "No input to parse")
                when (node.type) {
                    NodeType.JSON_PARSER -> ParserEngine.jsonPath(text, node.cfg("path"))
                    NodeType.HTML_PARSER -> ParserEngine.cssSelector(text, node.cfg("selector"), node.cfg("attr"))
                    NodeType.REGEX_PARSER -> ParserEngine.regex(text, node.cfg("pattern"), node.cfg("group").trim().toIntOrNull())
                    else -> ParserEngine.between(text, node.cfg("left"), node.cfg("right"))
                }
            }
        }
        return when (result) {
            is ParseResult.Failure -> fail("error", result.reason)
            is ParseResult.Success -> {
                ctx.variables[node.cfgOr("saveAs", "parsed")] = result.first
                ok("success")
            }
        }
    }

    private fun evaluate(left: String, op: String, right: String): Boolean = when (op) {
        "eq" -> left == right
        "neq" -> left != right
        "contains" -> left.contains(right)
        "starts_with" -> left.startsWith(right)
        "ends_with" -> left.endsWith(right)
        "gt" -> number(left) > number(right)
        "lt" -> number(left) < number(right)
        "regex" -> Regex(right).containsMatchIn(left)
        "empty" -> left.isEmpty()
        "not_empty" -> left.isNotEmpty()
        else -> throw IllegalArgumentException("Unknown operator: $op")
    }

    private fun number(s: String): Double = s.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Not a number: $s")

    private fun transform(value: String, op: String): String = when (op) {
        "trim" -> value.trim()
        "lowercase" -> value.lowercase()
        "uppercase" -> value.uppercase()
        "length" -> value.length.toString()
        "urlencode" -> URLEncoder.encode(value, "UTF-8")
        "urldecode" -> URLDecoder.decode(value, "UTF-8")
        "base64_encode" -> Base64.getEncoder().encodeToString(value.toByteArray())
        "base64_decode" -> String(Base64.getDecoder().decode(value))
        else -> throw IllegalArgumentException("Unknown transform: $op")
    }

    private fun validateValue(value: String, rule: String, arg: String): Boolean = when (rule) {
        "not_empty" -> value.isNotEmpty()
        "regex" -> Regex(arg).matches(value)
        "number" -> value.trim().toDoubleOrNull() != null
        "min_length" -> value.length >= (arg.trim().toIntOrNull() ?: throw IllegalArgumentException("Argument must be a number"))
        "max_length" -> value.length <= (arg.trim().toIntOrNull() ?: throw IllegalArgumentException("Argument must be a number"))
        else -> throw IllegalArgumentException("Unknown rule: $rule")
    }
}
