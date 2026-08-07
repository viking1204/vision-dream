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
    fun modelListUsesOpenAiFieldNamesAndStableOrdering() {
        val json = OpenAiJson.models(
            listOf(
                OpenAiModel(id = "模型-a", created = 10L),
                OpenAiModel(id = "model-b", created = 20L, ownedBy = "owner"),
            ),
        )

        assertEquals(
            """{"object":"list","data":[{"id":"模型-a","object":"model","created":10,"owned_by":"vision-dream"},{"id":"model-b","object":"model","created":20,"owned_by":"owner"}]}""",
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
    fun modelObjectEmitsOptionalMetadataAfterOwnedByInStableOrder() {
        val json = OpenAiJson.model(
            OpenAiModel(
                id = "anythingv5",
                created = 1754000000L,
                name = "Anything V5",
                type = "generation",
                backendType = "sd15npu",
                supportsImageInput = true,
            ),
        )

        assertEquals(
            """{"id":"anythingv5","object":"model","created":1754000000,"owned_by":"vision-dream","name":"Anything V5","type":"generation","backend_type":"sd15npu","supports_image_input":true}""",
            json,
        )
    }

    @Test
    fun modelObjectOmitsFalseImageInputFlagRatherThanDroppingField() {
        val json = OpenAiJson.model(
            OpenAiModel(
                id = "bare",
                created = 1L,
                supportsImageInput = false,
            ),
        )

        assertTrue(json.contains(""""supports_image_input":false"""))
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
