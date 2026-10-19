package me.helvert.codebridge.views

/** Transfers space below the toggled header so sections above it stay put. */
internal fun toggledViewStackWeights(weights: List<Double>, open: List<Boolean>, index: Int): List<Double> {
    val neighbor = (index + 1 until weights.size).firstOrNull { open[it] }
        ?: (index - 1 downTo 0).firstOrNull { open[it] }
        ?: return weights
    return weights.toMutableList().apply {
        if (open[index]) {
            this[neighbor] += this[index]
        } else {
            // Restore the previous size when possible. If the neighbor has since shrunk,
            // share its space instead of leaving it with a zero or negative height.
            val restored = this[index].takeIf { it < this[neighbor] } ?: (this[neighbor] / 2)
            this[index] = restored
            this[neighbor] -= restored
        }
    }
}
