import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const users = new SharedArray('Load Test Users', function () {
    return papaparse.parse(open('../loadtest-tokens.csv'), { header: true }).data;
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || 'aaaaaaaa-1111-1111-1111-111111111111';

export const options = {
    scenarios: {
        flash_sale_redis: {
            executor: 'per-vu-iterations',
            vus: 500,
            iterations: 1,
            maxDuration: '30s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.85'],  // Expect ~80% to get 409 (out of stock)
        http_req_duration: ['p(95)<100'],  // Should be MUCH faster than DB-only (< 100ms)
    },
};

export function setup() {
    // Pre-warm the product before the test
    const adminToken = users[0].token;  // Use first user as admin for pre-warm

    const preWarmRes = http.post(
        `${BASE_URL}/api/v1/flash-sale/pre-warm?productId=${PRODUCT_ID}`,
        null,
        {
            headers: {
                'Authorization': `Bearer ${adminToken}`,
            },
        }
    );

    check(preWarmRes, {
        'pre-warm succeeds': (r) => r.status === 200,
    });

    return { preWarmed: preWarmRes.status === 200 };
}

export default function () {
    const user = users[__VU - 1];
    if (!user) return;

    const payload = JSON.stringify({
        productId: PRODUCT_ID,
        quantity: 1,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${user.token}`,
        },
    };

    const res = http.post(`${BASE_URL}/api/v1/flash-sale/checkout`, payload, params);

    check(res, {
        'is 202 or 409': (r) => r.status === 202 || r.status === 409,
        'no 500 errors': (r) => r.status !== 500,
        'response under 100ms': (r) => r.timings.duration < 100,
    });
}