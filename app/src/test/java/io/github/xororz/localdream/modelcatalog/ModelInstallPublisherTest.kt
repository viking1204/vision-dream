package io.github.xororz.localdream.modelcatalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInstallPublisherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun existingTargetIsNeverReplaced() {
        val root = temporaryFolder.newFolder("models")
        val target = File(root, "portrait_model").apply {
            mkdirs()
            File(this, "installed.marker").writeText("keep")
        }
        val staging = temporaryFolder.newFolder("staging", "portrait_model-tmp").apply {
            File(this, "new.marker").writeText("new")
        }

        val outcome = ModelInstallPublisher.publish(staging, target)

        assertEquals(ModelInstallPublisher.Outcome.ALREADY_INSTALLED, outcome)
        assertEquals("keep", File(target, "installed.marker").readText())
        assertFalse(File(target, "new.marker").exists())
        assertTrue(staging.exists())
    }

    @Test
    fun validatedStagingDirectoryIsPublishedByRename() {
        val root = temporaryFolder.newFolder("models")
        val target = File(root, "portrait_model")
        val staging = temporaryFolder.newFolder("staging", "portrait_model-tmp").apply {
            File(this, "finished").writeText("done")
        }

        val outcome = ModelInstallPublisher.publish(staging, target)

        assertEquals(ModelInstallPublisher.Outcome.PUBLISHED, outcome)
        assertTrue(File(target, "finished").isFile)
        assertFalse(staging.exists())
    }
}
