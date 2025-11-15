package com.github.bsaltz.springai.examples.pdf

import java.io.Serializable

interface Refineable : Serializable {
    val changes: List<Change>?
}
