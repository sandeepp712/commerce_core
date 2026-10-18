import http from 'k6/http';
import {check} from 'k6';
import {SharedArray} from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';


http.setResponseCallback(http.expectedStatuses(200,202, 409));

// Load the 500 pre-generated users and tokens
const users = new SharedArray('Load Test Users', function () {
    return papaparse.parse(open('./loadtest-tokens.csv'), { header: true }).data;
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios:{
        flash_sale: {
            executor: 'per-vu-iterations',
            vus: 500,
            iterations: 1,
            maxDuration: '60s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],         // 1% network/server error
        http_req_duration: ['p(95)<2000'],      //95% of requests under 2 seconds

        'checks{type:outcome}':   ['rate==1'],         // every response was 200 or 409
        'checks{type:no_5xx}':    ['rate==1'],         // zero server errors
    },
};


export default function (){
    const user = users[__VU -1];
    if (!user) return;

    const payload = JSON.stringify({
        shippingAddressId: '00000000-0000-0000-0000-000000000000',
        idempotencyKey: `flash-sale-${user.userId}`,
        couponCode: null,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${user.token}`,
            'IdempotencyKey': `flash-sale-${user.userId}`,
        }
    };

    const res = http.post(`${BASE_URL}/api/v1/checkout`, payload ,params);

    // In a flash sale with 100 items and 500 users
    // ~100 users should get 201 Created
    // ~400 users should get 409 Conflict (Insufficient stock)
    // NO one should get 500 internal server error

    // ── 2. Two checks: outcome must be one of the expected two, and never 5xx
    check(res, {
        'outcome is 200 or 409': (r) => r.status === 202 || r.status === 409,
    }, { type: 'outcome' });

    check(res, {
        'no server errors': (r) => r.status < 500,
    }, { type: 'no_5xx' });
}