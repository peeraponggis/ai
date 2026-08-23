# ผลทดสอบ CORS — เรียก LLM ฟรีจากหน้าเว็บโดยตรง

รันบน GitHub Actions (ubuntu-latest) เมื่อ 2026-08-23 10:32 UTC
commit `e60a0044d6214d79cdecd55a09a5459937d57df2`

## ส่วนที่ 1 — HTTP header ดิบ (curl)

ตัวชี้ขาดคือ header `Access-Control-Allow-Origin` ในคำตอบ
ถ้าไม่มี = เบราว์เซอร์จะบล็อกทุกกรณี

### LLM7 — /v1/chat/completions

```
$ preflight OPTIONS https://api.llm7.io/v1/chat/completions  (Origin: https://example.com)
HTTP/2 200 
date: Sun, 23 Aug 2026 10:32:33 GMT
content-type: text/plain; charset=utf-8
content-length: 2
server: cloudflare
nel: {"report_to":"cf-nel","success_fraction":0.0,"max_age":604800}
access-control-allow-origin: *
access-control-allow-methods: DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT
access-control-max-age: 600
access-control-allow-headers: content-type,authorization
x-request-id: f3c74682269e82cd9e81ce58bc5ae1bb
cf-cache-status: DYNAMIC
report-to: {"group":"cf-nel","max_age":604800,"endpoints":[{"url":"https://a.nel.cloudflare.com/report/v4?s=N8JpQpYxN46FI3OwCj4gH3ai2%2BCXMYZVuOpCsOpqfgez7kqqUSTpEH0OnAFMsidik5KWvJPeH3C9fO1koQqbrcq4tW%2BbXSQeIYtYJezYQYtE%2Bis0rcKBTYIWeFnHnA%3D%3D"}]}
cf-ray: a2f97436cfeed836-ORD
alt-svc: h3=":443"; ma=86400

OK
```

### LLM7 — /v1/models

```
$ preflight OPTIONS https://api.llm7.io/v1/models  (Origin: https://example.com)
HTTP/2 204 
date: Sun, 23 Aug 2026 10:32:35 GMT
server: cloudflare
access-control-allow-origin: *
access-control-allow-methods: GET, OPTIONS
access-control-allow-headers: If-None-Match, Content-Type
access-control-max-age: 86400
cf-cache-status: DYNAMIC
report-to: {"group":"cf-nel","max_age":604800,"endpoints":[{"url":"https://a.nel.cloudflare.com/report/v4?s=%2BAP43r0nErRu92%2FFncAyoCehxzqjZhoc50b7%2FXd021A5h34HC4mCeT8MpBXpvIdxVGafUAZs7CBWWvZAW2cqnyX%2F1cYE3JC0T77q%2FkXNGxvyzj1vMD95N4FJQDqsQA%3D%3D"}]}
nel: {"report_to":"cf-nel","success_fraction":0.0,"max_age":604800}
cf-ray: a2f97444fb916182-ORD
alt-svc: h3=":443"; ma=86400


```

### Pollinations — /openai

```
$ preflight OPTIONS https://text.pollinations.ai/openai  (Origin: https://example.com)
HTTP/2 204 
date: Sun, 23 Aug 2026 10:32:38 GMT
cf-ray: a2f974529e5545f5-ORD
cf-cache-status: DYNAMIC
access-control-allow-origin: *
server: cloudflare
vary: Access-Control-Request-Headers
x-cache: MISS
x-cache-key: 6ab3b2e2986f4fe19d00e5efcbe389d798e47d6611b45533a834bd419bee9f18
access-control-allow-headers: content-type,authorization
access-control-allow-methods: GET,HEAD,PUT,PATCH,POST,DELETE
x-powered-by: Express
report-to: {"group":"cf-nel","max_age":604800,"endpoints":[{"url":"https://a.nel.cloudflare.com/report/v4?s=WR9qyDcAP%2Fee7z%2FdntpbeXhvTduLFrHrM6ADkiFnM6wDBOZpe7PD4NNOMF614yb5EVfN6G7Dtgo2a1kFImW2GFGOgMtS1mRbvHStLHuexL3OvbF843%2B9XhDoDHItUp5bPx116x2wgw%3D%3D"}]}
nel: {"report_to":"cf-nel","success_fraction":0.0,"max_age":604800}
alt-svc: h3=":443"; ma=86400


```

### คำขอจริง (POST) พร้อม Origin

```
HTTP/2 200 
date: Sun, 23 Aug 2026 10:32:40 GMT
content-type: application/json
content-length: 379
server: cloudflare
nel: {"report_to":"cf-nel","success_fraction":0.0,"max_age":604800}
x-content-type-options: nosniff
mistral-correlation-id: 01a02e2d-f9e2-7601-93f0-6258af2cef75
x-kong-request-id: 01a02e2d-f9e2-7601-93f0-6258af2cef75
x-max-retry-attempts-reached: false
x-envoy-upstream-service-time: 106
access-control-allow-origin: *
x-kong-upstream-latency: 107
x-kong-proxy-latency: 15
strict-transport-security: max-age=15552000; includeSubDomains; preload
cf-cache-status: DYNAMIC
x-request-id: 0cb9ce42a164dc98defc00793ad32289
alt-svc: h3=":443"; ma=86400
x-llm-gateway-cache: MISS
access-control-expose-headers: X-Request-ID
report-to: {"group":"cf-nel","max_age":604800,"endpoints":[{"url":"https://a.nel.cloudflare.com/report/v4?s=ZBUtOwhPy6BHPqw1D%2FS9k5mdGM%2BUOc4i7DvtSR0E5WYU%2F0mH%2FS9HzW1aw4DFsv2Zf6L0b%2FNdJHGL4ANuL8TbbjbSwym0oVT2sKY02oJEevdf5jENmRKMDsRtBoYRgw%3D%3D"}]}
cf-ray: a2f974623cf3d836-ORD

{"id":"chatcmpl_bf361737b7934f2bbefa882c55a4c989","created":1787481160,"model":"codestral-latest","usage":{"prompt_tokens":27,"total_tokens":34,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":0},"service_tier":"standard"},"object":"chat.completion","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","tool_calls":null,"content":"40,000"}}]}
```

## ส่วนที่ 2 — หน้าเทสต์จริงในเบราว์เซอร์ (Chromium, origin `http://localhost:8080`)

โหลดหน้าผ่าน http จริง ไม่ใช่ `file://` — Origin จึงไม่ใช่ null เหมือนกรณีโฮสต์จริง

```
node:internal/modules/cjs/loader:1433
  throw err;
  ^

Error: Cannot find module 'playwright'
Require stack:
- /tmp/run.js
    at Function._resolveFilename (node:internal/modules/cjs/loader:1430:15)
    at defaultResolveImpl (node:internal/modules/cjs/loader:1040:19)
    at resolveForCJSWithHooks (node:internal/modules/cjs/loader:1045:22)
    at Function._load (node:internal/modules/cjs/loader:1216:25)
    at wrapModuleLoad (node:internal/modules/cjs/loader:254:19)
    at Module.require (node:internal/modules/cjs/loader:1527:12)
    at require (node:internal/modules/helpers:147:16)
    at Object.<anonymous> (/tmp/run.js:1:22)
    at Module._compile (node:internal/modules/cjs/loader:1781:14)
    at Object..js (node:internal/modules/cjs/loader:1913:10) {
  code: 'MODULE_NOT_FOUND',
  requireStack: [ '/tmp/run.js' ]
}

Node.js v22.23.2
```

