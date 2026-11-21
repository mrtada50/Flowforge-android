package com.flowforge.android.execution

class UnresolvedVariableException(val variable: String) : Exception("Unresolved variable: $variable")

object VariableResolver {
    private val pattern = Regex("""\$\{([A-Za-z0-9_.\-]+)\}""")

    /** Replaces ${name}. An unknown variable is an error, never an empty string. */
    fun resolve(template: String, variables: Map<String, String>): String =
        pattern.replace(template) { m ->
            variables[m.groupValues[1]] ?: throw UnresolvedVariableException(m.groupValues[1])
        }
}
