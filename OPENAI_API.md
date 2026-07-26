# OpenAI-Compatible Image API

Start the service from **Device Link → OpenAI-compatible image API**. The
screen shows the device base URL and a generated bearer key. Keep the key
private: traffic is plain HTTP and should only be used on a trusted local
network. The bearer key authenticates callers but does not encrypt requests;
place the service behind a TLS reverse proxy before traffic crosses any
untrusted network.

For long-running use, leave the **Device Link** screen open and use its black
screen shield; this keeps the display logically on while minimizing OLED
light. Prefer external power. If the display is allowed to turn off, Android
Doze or vendor battery management may suspend LAN traffic unless the user
explicitly exempts Vision Dream from battery optimization.

Every route requires:

```http
Authorization: Bearer <key shown in the app>
```

Both fixed `Content-Length` and HTTP/1.1 chunked request bodies are accepted.
The decoded body is limited to 20 MiB, with a 40 MiB process-wide in-flight
budget.

Another app on the same Android device should use
`http://127.0.0.1:8809/v1`. A client on another device must use one of the LAN
addresses shown in Vision Dream. Android clients must also permit cleartext
HTTP for this address.

## Models

`GET /v1/models` (or `GET /models`) returns every complete installed generation
model and upscaler. It is not limited to the model currently loaded in memory.

```bash
curl -H "Authorization: Bearer $VISION_DREAM_KEY" \
  http://PHONE_IP:8809/v1/models
```

## Generate and Edit

Generate an image with `POST /v1/images/generations`:

```bash
curl http://PHONE_IP:8809/v1/images/generations \
  -H "Authorization: Bearer $VISION_DREAM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"MODEL_ID","prompt":"a paper-cut forest","size":"512x512","response_format":"b64_json"}'
```

Edit or perform image-to-image generation with multipart
`POST /v1/images/edits`:

```bash
curl http://PHONE_IP:8809/v1/images/edits \
  -H "Authorization: Bearer $VISION_DREAM_KEY" \
  -F model=MODEL_ID -F prompt="make it dusk" \
  -F image=@input.png -F mask=@mask.png \
  --output result.png
```

The optional Vision Dream parameters are `negative_prompt`, `steps`, `cfg`,
`seed`, `scheduler`, and `denoise_strength`. Only `n=1` and PNG generation
output are supported. If `response_format` is omitted or set to `url`, Vision
Dream returns standard JSON containing a temporary image URL. Explicit
`response_format=b64_json` returns OpenAI-style Base64 JSON, while
`response_format=binary` returns the image bytes directly with the correct
image content type:

```bash
response=$(curl http://PHONE_IP:8809/v1/images/generations \
  -H "Authorization: Bearer $VISION_DREAM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"MODEL_ID","prompt":"a paper-cut forest","response_format":"url"}')

curl "$(printf '%s' "$response" | jq -r '.data[0].url')" --output result.png
```

The temporary URL contains an unguessable bearer token, requires no additional
Authorization header, and expires after 10 minutes or when the API service
restarts. Every successful API result is also saved in the app's Asset manager.

## Upscale Extension

OpenAI does not define an upscale route. Vision Dream provides the multipart
extension `POST /v1/images/upscales`; `model` must be an installed upscaler ID:

```bash
curl http://PHONE_IP:8809/v1/images/upscales \
  -H "Authorization: Bearer $VISION_DREAM_KEY" \
  -F model=upscaler_realistic -F image=@input.png \
  --output result.png
```

Generation, edit, and upscale requests share one model-aware priority queue.
One request runs at a time; waiting requests for the currently loaded model are
promoted ahead of requests that require a model switch. Requests within the
same priority keep arrival order, and after three promotions the queue head is
forced to run so another model cannot starve. The configured 0–10 capacity
counts waiting requests only. Overflow returns HTTP `429` with code
`queue_full`; changes take effect after restarting the service. The native
pipeline is single-instance and is not safe for parallel inference, even for
the same model. Concurrent HTTP callers are accepted and queued, but inference
remains serial. Starting an in-app generation while API work is active is
rejected, and API calls receive HTTP `409` while the app owns the pipeline;
this prevents either side from switching the model under the other.

The upscale extension also supports both response modes; its current native
output is JPEG.

Multipart bodies are limited to 25 MiB and each uploaded file to 20 MiB. Edit
images and masks are limited to 8,192 pixels per edge and 16,777,216 total
pixels; upscale inputs use the same edge limit and a 1,048,576-pixel limit.
