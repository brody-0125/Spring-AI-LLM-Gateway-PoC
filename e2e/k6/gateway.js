import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate } from 'k6/metrics';

const scenario = __ENV.K6_SCENARIO || 'smoke';
const baseUrl = (__ENV.K6_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const apiKey = __ENV.K6_GATEWAY_API_KEY;
const model = __ENV.K6_MODEL || 'default';
const prompt = __ENV.K6_PROMPT || 'Return a short health-check response.';
const stream = scenario === 'stream';
const unexpectedResponses = new Rate('gateway_unexpected_responses');

if (!['smoke', 'stream', 'rate-limit'].includes(scenario)) {
  fail(`Unsupported K6_SCENARIO: ${scenario}`);
}

export const options = {
  scenarios: {
    gateway: scenario === 'rate-limit'
      ? {
          executor: 'constant-vus',
          vus: Number(__ENV.K6_VUS || 10),
          duration: __ENV.K6_DURATION || '10s',
        }
      : {
          executor: 'constant-vus',
          vus: Number(__ENV.K6_VUS || 1),
          duration: __ENV.K6_DURATION || '30s',
        },
  },
  thresholds: {
    ...(scenario === 'rate-limit' ? {} : { http_req_failed: ['rate<0.01'] }),
    gateway_unexpected_responses: ['rate<0.01'],
  },
};

export default function () {
  if (!apiKey) {
    fail('K6_GATEWAY_API_KEY must be supplied through the environment.');
  }

  const response = http.post(
    `${baseUrl}/v1/chat/completions`,
    JSON.stringify({
      model,
      stream,
      messages: [{ role: 'user', content: prompt }],
    }),
    {
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
        Accept: stream ? 'text/event-stream' : 'application/json',
      },
      timeout: __ENV.K6_TIMEOUT || '90s',
    },
  );

  const expected = scenario === 'rate-limit' ? [200, 429] : [200];
  const valid = check(response, {
    'gateway returns an expected status': (res) => expected.includes(res.status),
    'gateway returns a request id': (res) => Boolean(res.headers['X-Request-Id']),
    ...(stream
      ? {
          'stream response has SSE content type': (res) =>
            String(res.headers['Content-Type'] || '').includes('text/event-stream'),
          'stream response terminates with DONE': (res) => String(res.body).includes('[DONE]'),
        }
      : {
          'json response has completion choices': (res) => String(res.body).includes('"choices"'),
          'json response has completion object': (res) => String(res.body).includes('"object":"chat.completion"'),
        }),
  });

  unexpectedResponses.add(!valid);
}
