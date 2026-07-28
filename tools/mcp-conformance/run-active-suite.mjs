import http from "node:http";
import { spawn, spawnSync } from "node:child_process";

const argumentsByName = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const name = process.argv[index];
  const value = process.argv[index + 1];
  if (!name?.startsWith("--") || !value || argumentsByName.has(name)) {
    throw new Error("Usage: node run-active-suite.mjs --serial <ANDROID_SERIAL> --base-url <MCP_URL> --token <MCP_TEST_TOKEN>");
  }
  argumentsByName.set(name, value);
}

const serial = argumentsByName.get("--serial");
const baseUrl = argumentsByName.get("--base-url");
const token = argumentsByName.get("--token");
if (!serial || !baseUrl || !token || argumentsByName.size !== 3) {
  throw new Error("--serial, --base-url and --token are all required");
}

const target = new URL(baseUrl);
if (target.protocol !== "http:" || !isLoopbackOrPrivateLan(target.hostname)) {
  throw new Error("--base-url must be an explicit http loopback or private-LAN MCP endpoint");
}

const device = spawnSync("adb", ["-s", serial, "get-state"], { encoding: "utf8" });
if (device.status !== 0 || device.stdout.trim() !== "device") {
  throw new Error(`Android device ${serial} is unavailable`);
}

const proxy = http.createServer((request, response) => {
  const headers = {
    ...request.headers,
    authorization: `Bearer ${token}`,
  };
  // The proxy's ordinary Host header names the proxy itself, not the MCP
  // listener. Translate only that default value; preserve conformance's
  // explicit hostile Host/Origin inputs so DNS-rebinding checks reach Android.
  if (headers.host === `127.0.0.1:${address.port}` || headers.host === `localhost:${address.port}`) {
    headers.host = target.host;
  }
  if (!headers.origin) headers.origin = `${target.protocol}//${target.host}`;
  const upstream = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      method: request.method,
      path: request.url,
      headers,
    },
    (upstreamResponse) => {
      response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers);
      upstreamResponse.pipe(response);
    },
  );
  upstream.on("error", (error) => {
    response.writeHead(502, { "content-type": "text/plain" });
    response.end(`MCP upstream unavailable: ${error.message}`);
  });
  request.pipe(upstream);
});

await new Promise((resolve) => proxy.listen(0, "127.0.0.1", resolve));
const address = proxy.address();
if (!address || typeof address === "string") throw new Error("Could not allocate conformance proxy port");

const resultDirectory = `results/active-${new Date().toISOString().replaceAll(":", "-")}`;
const runner = spawn(
  "npm",
  ["exec", "--no", "--", "conformance", "server", "--url", `http://127.0.0.1:${address.port}/mcp`, "--suite", "active", "--expected-failures", "expected-failures.yaml", "--output-dir", resultDirectory],
  { stdio: "inherit" },
);
const exitCode = await new Promise((resolve) => runner.on("exit", (code) => resolve(code ?? 1)));
await new Promise((resolve) => proxy.close(resolve));
process.exitCode = exitCode;

function isLoopbackOrPrivateLan(hostname) {
  if (hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1") return true;
  if (/^10\./.test(hostname) || /^192\.168\./.test(hostname)) return true;
  const match = /^172\.(\d+)\./.exec(hostname);
  return match !== null && Number(match[1]) >= 16 && Number(match[1]) <= 31;
}
