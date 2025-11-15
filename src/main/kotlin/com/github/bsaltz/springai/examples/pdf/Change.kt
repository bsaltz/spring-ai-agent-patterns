package com.github.bsaltz.springai.examples.pdf

import java.io.Serializable

data class Change(
    val field: String,
    val oldValue: String?,
    val rationale: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
