package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelUsageRankingTest {
    @Test
    fun usageOrderTakesPriorityAndUnusedPinsRemainAheadOfUnusedModels() {
        val models = listOf("alpha", "beta", "gamma", "delta").map(::model)

        val sorted = ModelUsageRanking.sort(
            models = models,
            usageIds = listOf("gamma", "alpha"),
            pinnedIds = listOf("delta", "gamma"),
        )

        assertEquals(
            listOf("gamma", "alpha", "delta", "beta"),
            sorted.map(Model::id),
        )
    }

    @Test
    fun unknownRanksAreIgnoredAndOriginalOrderRemainsStable() {
        val models = listOf("alpha", "beta", "gamma").map(::model)

        val sorted = ModelUsageRanking.sort(
            models = models,
            usageIds = listOf("missing"),
            pinnedIds = emptyList(),
        )

        assertEquals(models, sorted)
    }

    private fun model(id: String) = Model(
        id = id,
        name = id,
        description = "",
        baseUrl = "",
    )
}
