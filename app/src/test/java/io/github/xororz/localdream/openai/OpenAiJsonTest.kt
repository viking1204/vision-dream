package io.github.xororz.localdream.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiJsonTest {
    @Test
    fun errorEnvelopeEscapesUntrustedTextAndKeepsNullableFields() {
        val json = OpenAiJson.error(
            OpenAiError(
                message = "bad\n\"path\\\u0001\u2028",
                param = "prompt",
            ),
        )

        assertEquals(
            """{"error":{"message":"bad\n\"path\\\u0001\u2028","type":"invalid_request_error","param":"prompt","code":null}}""",
            json,
        )
    }

    @Test
    fun modelListCarriesTypeAlongsideTheStandardFieldsAndNoOtherExtension() {
        val json = OpenAiJson.models(
            listOf(
                OpenAiModel(
                    id = "模型-a",
                    created = 10L,
                    name = "A",
                    type = "image",
                    backendType = "sdxl",
                    capabilities = OpenAiModel.ModelCapabilities(true, true, false),
                    tags = listOf("动漫", "可爱"),
                ),
                OpenAiModel(id = "model-b", created = 20L, ownedBy = "owner"),
            ),
        )

        // Image clients filter the picker on `type == "image"`, so the list has
        // to carry `type`. `tags` rides along too because per-model round trips
        // are not viable for style filtering over a 60+ model catalog. Every
        // other vendor field stays out of the list to keep the payload lean for
        // strict deserializers.
        assertEquals(
            """{"object":"list","data":[{"id":"模型-a","object":"model","created":10,"owned_by":"vision-dream",""" +
                """"type":"image","tags":["动漫","可爱"]},""" +
                """{"id":"model-b","object":"model","created":20,"owned_by":"owner"}]}""",
            json,
        )
    }

    @Test
    fun modelListOmitsTagsWhenNoKeywordMatched() {
        val json = OpenAiJson.models(
            listOf(OpenAiModel(id = "plain", created = 1L, type = "image")),
        )

        // An empty array would force clients to distinguish "no tags" from
        // "untagged"; omitting the key keeps the payload honest and smaller.
        assertEquals(
            """{"object":"list","data":[{"id":"plain","object":"model","created":1,"owned_by":"vision-dream","type":"image"}]}""",
            json,
        )
    }

    @Test
    fun singleModelSerializesCoreFieldsOnlyWhenOptionalAbsent() {
        val json = OpenAiJson.model(OpenAiModel(id = "model-x", created = 5L))

        assertEquals(
            """{"id":"model-x","object":"model","created":5,"owned_by":"vision-dream"}""",
            json,
        )
    }

    @Test
    fun singleModelWrapsMetadataUnderNamespacedExtensionKey() {
        val json = OpenAiJson.model(
            OpenAiModel(
                id = "anythingv5",
                created = 1754000000L,
                name = "Anything V5",
                type = "image",
                backendType = "sd15npu",
                capabilities = OpenAiModel.ModelCapabilities(
                    imageGeneration = true,
                    imageEdit = true,
                    imageUpscale = false,
                ),
                tags = listOf("动漫", "写实"),
            ),
        )

        assertEquals(
            """{"id":"anythingv5","object":"model","created":1754000000,"owned_by":"vision-dream","type":"image",""" +
                """"tags":["动漫","写实"],""" +
                """"x-vision-dream":{"name":"Anything V5","type":"image","backend_type":"sd15npu",""" +
                """"tags":["动漫","写实"],""" +
                """"capabilities":{"image_generation":true,"image_edit":true,"image_upscale":false}}}""",
            json,
        )
    }

    @Test
    fun singleModelEmitsCapabilitiesBlockWithFalseFlags() {
        val json = OpenAiJson.model(
            OpenAiModel(
                id = "up",
                created = 1L,
                type = "upscaler",
                backendType = "upscaler",
                capabilities = OpenAiModel.ModelCapabilities(
                    imageGeneration = false,
                    imageEdit = false,
                    imageUpscale = true,
                ),
            ),
        )

        assertTrue(
            json.contains(
                """"x-vision-dream":{"type":"upscaler","backend_type":"upscaler","capabilities":{"image_generation":false,"image_edit":false,"image_upscale":true}}""",
            ),
        )
    }

    @Test
    fun generationModelWithoutImageEncoderAdvertisesNoImageEdit() {
        val json = OpenAiJson.model(
            OpenAiModel(
                id = "txt2img-only",
                created = 2L,
                type = "generation",
                backendType = "sdxl",
                capabilities = OpenAiModel.ModelCapabilities(
                    imageGeneration = true,
                    imageEdit = false,
                    imageUpscale = false,
                ),
            ),
        )

        assertTrue(
            json.contains(
                """"capabilities":{"image_generation":true,"image_edit":false,"image_upscale":false}""",
            ),
        )
    }

    @Test
    fun imageEnvelopeIncludesOnlyPresentOptionalFields() {
        val json = OpenAiJson.images(
            created = 123L,
            images = listOf(
                OpenAiImage(b64Json = "YWJj"),
                OpenAiImage(b64Json = "ZGVm", revisedPrompt = "cleaned"),
            ),
        )

        assertEquals(
            """{"created":123,"data":[{"b64_json":"YWJj"},{"b64_json":"ZGVm","revised_prompt":"cleaned"}]}""",
            json,
        )
    }

    @Test
    fun imageEnvelopeSupportsTemporaryUrls() {
        val json = OpenAiJson.images(
            created = 123L,
            images = listOf(
                OpenAiImage(url = "http://127.0.0.1:8809/v1/images/files/token"),
            ),
        )

        assertEquals(
            """{"created":123,"data":[{"url":"http://127.0.0.1:8809/v1/images/files/token"}]}""",
            json,
        )
    }

    @Test
    fun imageEnvelopeExposesOnlyPositiveNativeVendorDiagnostics() {
        val json = OpenAiJson.images(
            created = 123L,
            images = listOf(OpenAiImage(b64Json = "YWJj")),
            diagnostics = NativeGenerationDiagnostics(unetMs = 456L),
        )

        assertEquals(
            """{"created":123,"data":[{"b64_json":"YWJj"}],"vendor_diagnostics":{"unet_ms":456}}""",
            json,
        )
        val absent = OpenAiJson.images(
            created = 123L,
            images = listOf(OpenAiImage(b64Json = "YWJj")),
            diagnostics = NativeGenerationDiagnostics(unetMs = 0L),
        )
        assertTrue(!absent.contains("vendor_diagnostics"))
    }

    @Test
    fun surrogatePairsAreEscapedAsValidJsonUnicodeEscapes() {
        val json = OpenAiJson.error(OpenAiError(message = "😀"))

        assertTrue(json.contains("""\ud83d\ude00"""))
    }
}
