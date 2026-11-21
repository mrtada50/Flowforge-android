package com.flowforge.android.workflow.validation

import com.flowforge.android.workflow.model.ConnectionDef
import com.flowforge.android.workflow.model.NodeSpecs
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.model.WorkflowDef

enum class Severity { ERROR, WARNING }

data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val nodeId: String? = null,
)

object WorkflowValidator {
    /** Edges leaving these ports may point backwards; every other cycle is an error. */
    private val backEdgeSources = setOf(NodeType.LOOP to "body", NodeType.INPUT_DATASET to "row")

    fun isValid(issues: List<ValidationIssue>) = issues.none { it.severity == Severity.ERROR }

    fun validate(wf: WorkflowDef): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun err(code: String, msg: String, nodeId: String? = null) =
            issues.add(ValidationIssue(Severity.ERROR, code, msg, nodeId))
        fun warn(code: String, msg: String, nodeId: String? = null) =
            issues.add(ValidationIssue(Severity.WARNING, code, msg, nodeId))

        // ids
        wf.nodes.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { err("DUPLICATE_NODE_ID", "Duplicate node id: $it", it) }
        wf.connections.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { err("DUPLICATE_CONNECTION_ID", "Duplicate connection id: $it") }
        val byId = wf.nodes.associateBy { it.id }

        // start / end
        val starts = wf.nodes.filter { it.type == NodeType.START }
        if (starts.size != 1) err("START_COUNT", "A workflow needs exactly one Start node (found ${starts.size})")
        if (wf.nodes.none { it.type == NodeType.END || it.type == NodeType.STOP }) {
            err("NO_END", "A workflow needs at least one End or Stop node")
        }

        // connections
        for (c in wf.connections) {
            val from = byId[c.fromNode]
            val to = byId[c.toNode]
            if (from == null || to == null) {
                err("DANGLING_CONNECTION", "Connection ${c.id} references a node that does not exist")
                continue
            }
            if (c.fromNode == c.toNode) err("SELF_CONNECTION", "${from.name} is connected to itself", from.id)
            if (c.fromPort !in NodeSpecs.of(from.type).outputs) {
                err("BAD_OUTPUT_PORT", "${from.name} has no output port '${c.fromPort}'", from.id)
            }
            if (c.toPort !in NodeSpecs.of(to.type).inputs) {
                err("BAD_INPUT_PORT", "${to.name} has no input port '${c.toPort}'", to.id)
            }
        }
        wf.connections.groupBy { it.fromNode to it.fromPort }.filter { it.value.size > 1 }.forEach { (k, _) ->
            err("MULTIPLE_OUTPUT_CONNECTIONS", "Output port '${k.second}' has more than one connection", k.first)
        }

        // required config
        for (n in wf.nodes) {
            for (field in NodeSpecs.of(n.type).fields) {
                if (field.required && n.config[field.key].isNullOrBlank()) {
                    err("MISSING_FIELD", "${n.name}: '${field.label}' is required", n.id)
                }
            }
        }

        // unconnected outputs
        for (n in wf.nodes) {
            for (port in NodeSpecs.of(n.type).outputs) {
                if (port != "error" && wf.connections.none { it.fromNode == n.id && it.fromPort == port }) {
                    warn("UNCONNECTED_OUTPUT", "${n.name}: output '$port' is not connected", n.id)
                }
            }
        }

        // reachability
        if (starts.size == 1) {
            val seen = HashSet<String>()
            val queue = ArrayDeque(listOf(starts[0].id))
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!seen.add(id)) continue
                wf.connections.filter { it.fromNode == id }.forEach { queue.addLast(it.toNode) }
            }
            wf.nodes.filter { it.id !in seen }.forEach { warn("UNREACHABLE", "${it.name} is not reachable from Start", it.id) }
        }

        if (hasForbiddenCycle(wf)) err("CYCLE", "The graph has a cycle that does not go through a loop body")
        return issues
    }

    private fun hasForbiddenCycle(wf: WorkflowDef): Boolean {
        val byId = wf.nodes.associateBy { it.id }
        val adj = HashMap<String, MutableList<String>>()
        for (c in wf.connections) {
            val from = byId[c.fromNode] ?: continue
            if (byId[c.toNode] == null) continue
            if ((from.type to c.fromPort) in backEdgeSources) continue
            adj.getOrPut(c.fromNode) { mutableListOf() }.add(c.toNode)
        }
        val color = HashMap<String, Int>()
        fun dfs(n: String): Boolean {
            color[n] = 1
            for (m in adj[n].orEmpty()) {
                val c = color[m]
                if (c == 1) return true
                if (c == null && dfs(m)) return true
            }
            color[n] = 2
            return false
        }
        return wf.nodes.any { color[it.id] == null && dfs(it.id) }
    }

    /** null = the connection is allowed, otherwise the reason it is refused. */
    fun canConnect(wf: WorkflowDef, fromNode: String, fromPort: String, toNode: String, toPort: String): String? {
        val from = wf.nodes.firstOrNull { it.id == fromNode } ?: return "Source node does not exist"
        val to = wf.nodes.firstOrNull { it.id == toNode } ?: return "Target node does not exist"
        if (fromNode == toNode) return "A node cannot connect to itself"
        if (fromPort !in NodeSpecs.of(from.type).outputs) return "${from.name} has no output port '$fromPort'"
        if (toPort !in NodeSpecs.of(to.type).inputs) return "${to.name} has no input port '$toPort'"
        if (wf.connections.any { it.fromNode == fromNode && it.fromPort == fromPort }) {
            return "Output port '$fromPort' is already connected"
        }
        val trial = wf.copy(connections = wf.connections + ConnectionDef("trial", fromNode, fromPort, toNode, toPort))
        if (validate(trial).any { it.code == "CYCLE" }) return "This connection would create a cycle outside a loop"
        return null
    }
}
