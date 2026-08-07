package io.github.xororz.localdream.ui.screens

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.data.UpscalerBackend
import io.github.xororz.localdream.data.UpscalerModel
import io.github.xororz.localdream.service.ModelDownloadService
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime verification of the ZIP-packaged QNN upscaler download + extraction
 * path introduced by this feature branch. Drives the real ModelDownloadService
 * (the same code the UI uses) and asserts the archive is unpacked to
 * upscaler.bin via findUpscalerWeight / rename.
 */
@RunWith(AndroidJUnit4::class)
class UpscalerZipExtractInstrumentedTest {

    @Test
    fun mrjQnnZipDownloadsAndExtractsToUpscalerBin() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

        // The app stores models under /sdcard/VisionDream/models and requires
        // MANAGE_EXTERNAL_STORAGE (All-files access). On locked-down ROMs (e.g.
        // ColorOS) this cannot be granted via adb, so skip cleanly instead of
        // failing the suite — run manually after granting it in system settings.
        assumeTrue(
            "skipped: app lacks All-files access on this device " +
                "(grant in Settings > Apps > Vision Dream > All files access)",
            ModelStorage.hasAccess(context),
        )

        val model = UpscalerModel(
            id = "upscaler_mrj_realesrgan_x4plus",
            name = "RealESRGAN x4plus (Mr-J-369)",
            description = "QNN upscaler",
            baseUrl = "https://huggingface.co/",
            fileUri = "Mr-J-369/RealESRGAN_x4plus-Upscale/resolve/main/" +
                "upscaler_realistic_qnn2.28_8gen2.zip",
            backend = UpscalerBackend.QNN_NPU,
            isZip = true,
        )

        val target = File(Model.getModelsDir(context), "${model.id}/upscaler.bin")

        var lastState: ModelDownloadService.DownloadState? = null
        val observer = launch {
            ModelDownloadService.downloadState.collect { lastState = it }
        }

        try {
            model.startDownload(context)

            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (System.currentTimeMillis() < deadline) {
                if (target.exists() && target.length() > 0) return@runBlocking
                val s = lastState
                if (s is ModelDownloadService.DownloadState.Error) {
                    fail("download/extract failed: ${s.message}")
                }
                delay(3000)
            }
            fail(
                "timeout waiting for upscaler.bin; lastState=$lastState; " +
                    "exists=${target.exists()}",
            )
        } finally {
            observer.cancel()
        }
    }
}
