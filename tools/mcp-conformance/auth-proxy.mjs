import http from "node:http";

const [targetUrl, token, listenPort] = process.argv.slice(2);
if (!targetUrl || !token || !listenPort) {
  throw new Error("Usage: node auth-proxy.mjs <target-url> <bearer-token> <listen-port>");
}

const target = new URL(targetUrl);
const server = http.createServer((request, response) => {
  const upstream = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      method: request.method,
      path: request.url,
      headers: {
        ...request.headers,
        host: target.host,
        origin: `${target.protocol}//${target.host}`,
        authorization: `Bearer ${token}`,
      },
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

server.listen(Number(listenPort), "127.0.0.1", () => {
  process.stdout.write(`READY http://127.0.0.1:${listenPort}/mcp\n`);
});
