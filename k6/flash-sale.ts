import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

// ─── THE MAGIC OF SHAREARRAY ─────────────────────────────────────────────
// If we used a normal JS array, k6 would copy the 500 rows into the memory
// of EVERY Virtual User (500 VUs * 500 rows = 250,000 rows in RAM).
// SharedArray loads the CSV ONCE in memory and shares it across all VUs.
// This prevents Out-Of-Memory crashes on your local laptop.
const users = new SharedArray('Load Test Users', function () {
    return papaparse.parse(open('./loadtest-tokens.csv'), { header: true }).data;
});

export const options = {
    scenarios: {
        flash_sale: {
            executor: 'per-vu-iterations',
            vus: 500,              // 500 concurrent virtual users
            iterations: 1,         // Each VU executes the default function exactly once
            maxDuration: '120s',   // Safety timeout
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],     // < 1% network/server errors
        http_req_duration: ['p(95)<3000'],  // 95% of requests under 3s (relaxed for AWS network latency)
    },
};

export default function () {
    // __VU is the Virtual User ID (1 to 500).
    // We use __VU - 1 to map to the 0-indexed CSV array.
    // This guarantees VU #1 ALWAYS uses User #1's token and cart.
    const user = users[__VU - 1];

    if (!user || !user.token) {
        console.error(`Missing data for VU ${__VU}`);
        return;
    }

    const payload = JSON.stringify({
        shippingAddressId: '00000000-0000-0000-0000-000000000000',
        idempotencyKey: `flash-sale-${user.userId}`, // Unique per user
        couponCode: null,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${user.token}`, // Pre-generated token!
            'IdempotencyKey': `flash-sale-${user.userId}`,
        },
    };

    // ⚠️ REPLACE THIS WITH YOUR ACTUAL AWS EC2 PUBLIC IP
    const AWS_EC2_IP = '16.112.60.173';
    const res = http.post(`http://${AWS_EC2_IP}:8080/api/v1/checkout`, payload, params);

    if (res.status !== 201 && res.status !== 409) {
        console.log(`VU ${__VU} FAILED | Status: ${res.status} | Body: ${res.body}`);
    }

    // In a flash sale with 100 items and 500 users:
    // ~100 users will get 201 Created
    // ~400 users will get 409 Conflict (Insufficient Stock)
    // NO ONE should get 500 Internal Server Error
    check(res, {
        'is success or out of stock': (r) => r.status === 201 || r.status === 202 || r.status === 409,
        'no server errors': (r) => r.status !== 500,
    });
}