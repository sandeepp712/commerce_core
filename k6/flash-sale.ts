import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

// ─── CUSTOM METRICS ──────────────────────────────────────────────────────
const checkoutDuration = new Trend('checkout_response_duration', true);
const successfulCheckouts = new Counter('successful_checkouts_total');
const outOfStockConflicts = new Counter('out_of_stock_conflicts_total');
const unexpectedErrors = new Counter('unexpected_server_errors_total');
const successRate = new Rate('checkout_success_rate');

// ─── LOAD USERS ONCE INTO MEMORY ─────────────────────────────────────────
const users = new SharedArray('Load Test Users', function () {
    return papaparse.parse(open('./loadtest-tokens.csv'), { header: true }).data;
});

export const options = {
    scenarios: {
        flash_sale: {
            // 'per-vu-iterations' ensures each VU runs EXACTLY 1 iteration.
            // With 500 VUs and 1 iteration each, __VU cleanly maps 1:1 to your CSV rows.
            executor: 'per-vu-iterations',
            vus: 500,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    thresholds: {
        http_req_duration: ['p(99)<3000', 'p(95)<3000'],
        'checkout_response_duration': ['p(99)<2500'],
        'unexpected_server_errors_total': ['count==0'],
        'checkout_success_rate': ['rate > 0.05'],
    },
};

export default function () {
    // __VU ranges from 1 to 500. We subtract 1 for 0-indexed CSV rows.
    // VU #1 gets user[0], VU #2 gets user[1], ..., VU #500 gets user[499].
    const userIndex = __VU - 1;
    const user = users[userIndex];

    if (!user || !user.token) {
        console.error(`Missing data for VU ${__VU} (Index ${userIndex})`);
        return;
    }

    // Log the first 3 users to verify unique mapping
    if (userIndex < 3) {
        console.log(`VU ${__VU} mapped to User ID: ${user.userId}`);
    }

    const payload = JSON.stringify({
        shippingAddressId: '00000000-0000-0000-0000-000000000000',
        idempotencyKey: `flash-sale-${user.userId}`, // Unique per user
        couponCode: null,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${user.token}`,
            'Idempotency-Key': `flash-sale-${user.userId}`,
        },
    };

    // ⚠️ YOUR AWS EC2 PUBLIC IP
    const AWS_EC2_IP = '16.112.59.10';
    const res = http.post(`http://localhost:8080/api/v1/checkout`, payload, params);

    checkoutDuration.add(res.timings.duration);

    if (res.status === 201 || res.status === 202) {
        successfulCheckouts.add(1);
        successRate.add(true);
    } else if (res.status === 409) {
        outOfStockConflicts.add(1);
        successRate.add(false);
    } else {
        unexpectedErrors.add(1);
        successRate.add(false);
        console.log(`❌ VU ${__VU} ERROR | Status: ${res.status} | Body: ${res.body}`);
    }

    check(res, {
        'is valid status code (success or conflict)': (r) => [201, 202, 409].includes(r.status),
        'no backend crash (500)': (r) => r.status !== 500,
    });
}