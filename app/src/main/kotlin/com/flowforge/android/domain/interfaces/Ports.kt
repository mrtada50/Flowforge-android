package com.flowforge.android.domain.interfaces

import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.domain.models.HttpResult

interface HttpClientPort {
    /** Throws HttpEngineException for blocked/invalid/network/timeout failures. */
    suspend fun execute(spec: HttpRequestSpec): HttpResult
}

interface DatasetProvider {
    /** Next row of the named dataset, or null when exhausted. */
    suspend fun nextRow(datasetName: String): String?
}
