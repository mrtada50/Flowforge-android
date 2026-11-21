package com.flowforge.android.workflow

import com.flowforge.android.workflow.model.ConnectionDef
import com.flowforge.android.workflow.model.ExecutionSettings
import com.flowforge.android.workflow.model.NodeDef
import com.flowforge.android.workflow.model.NodeSpecs
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.model.WorkflowDef

/** node with default config overridden by the given pairs */
fun n(id: String, type: NodeType, vararg cfg: Pair<String, String>) =
    NodeDef(id, type, id, 0f, 0f, NodeSpecs.defaultConfig(type) + cfg.toMap())

fun c(from: String, port: String, to: String) = ConnectionDef("$from-$port-$to", from, port, to, "in")

fun wf(
    nodes: List<NodeDef>,
    conns: List<ConnectionDef>,
    settings: ExecutionSettings = ExecutionSettings(),
    vars: Map<String, String> = emptyMap(),
) = WorkflowDef(id = "wf", name = "test", nodes = nodes, connections = conns, variables = vars, settings = settings)
