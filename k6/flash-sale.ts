import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

// ─── THE MAGIC OF SHAREARRAY ─────────────────────────────────────────────
// If we used a normal JS array, k6 would copy the 500 rows into the memory
// of EVERY Virtual User (500 VUs * 500 rows = 250,000 rows in RAM).
// SharedArray loads the CSV ONCE in memory and shares it across all VUs.
// This prevents Out-Of-Memory crashes on your local laptop.
const users = new SharedArray('Load Test Users', function () {
    return papaparse.parse(open('./loadtest-tokens.csv'), { header: true }).data;
});


const checkoutDuration = new Trend('checkout_response_duration', true);
const successfulCheckouts = Counter('successful_checkouts_total');
const outOfStockConflicts = Counter('out_of_stock_conflicts_total');
const unexpectedErrors = Counter('unexpected_server_errors_total');
const successRate = new Rate('checkout_success_rate');

export const options = {
    scenarios: {
        flash_sale: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },   // Ramp to 100
                { duration: '1m', target: 100 },    // Hold 100
                { duration: '30s', target: 300 },   // Ramp to 300
                { duration: '1m', target: 300 },    // Hold 300
                { duration: '30s', target: 500 },   // Ramp to 500
                { duration: '1m', target: 500 },    // Hold 500
                { duration: '30s', target: 1000 },  // Ramp to 1000 (Expect breaking point)
                { duration: '1m', target: 1000 },   // Hold 1000
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(99)<3000','p(95)<3000'],
        'checkout_response_duration': ['p(99)<2500'],
        'unexpected_server_errors_total': ['count==0'],
        'checkout_success_rate': ['rate > 0.05'],
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
            'Idempotency-Key': `flash-sale-${user.userId}`,
        },
    };

    // ⚠️ REPLACE THIS WITH YOUR ACTUAL AWS EC2 PUBLIC IP
    const AWS_EC2_IP = '40.192.105.109';
    const res = http.post(`http://localhost:8080/api/v1/checkout`, payload, params);

    checkoutDuration.add(res.timings.duration);

    if (res.status === 201 || res.status === 202) {
        successfulCheckouts.add(1);
        successRate.add(true);
    }else if(res.status === 409){
        outOfStockConflicts.add(1);
        successRate.add(false);
    }else{
        unexpectedErrors.add(1);
        successRate.add(false);
        console.log(`❌ VU ${__VU} UNEXPECTED ERROR | Status: ${res.status} | Body: ${res.body}`)
    }

    // In a flash sale with 100 items and 500 users:
    // ~100 users will get 201 Created
    // ~400 users will get 409 Conflict (Insufficient Stock)
    // NO ONE should get 500 Internal Server Error
    check(res, {
        'is valid status code (success or conflict)': (r) => [201,202,409].includes(r.status),
        'no backend crash (500)': (r) => r.status !== 500,
    });

    // Brief jitter to mimic real-user pacing
    sleep(0.5);
}