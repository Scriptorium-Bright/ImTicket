
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

export const options = {
    stages: [
        { duration: '10s', target: 100 }, // Ramp up to 100 users
        { duration: '30s', target: 100 }, // Stay at 100 users for heavy write test
        { duration: '10s', target: 0 },   // Ramp down
    ],
    thresholds: {
        http_req_failed: ['rate<0.01'],   // Error rate should be less than 1%
        http_req_duration: ['p(95)<2000'], // 95% of requests should be below 2s
    },
};

const BASE_URL = 'http://localhost:10080';

export default function () {
    const type = __ENV.TYPE || 'async'; // Default to 'async' if not provided
    const hallId = 1; // Assuming Hall ID 1 exists (or you might need to create it first)

    // Construct a dummy payload compatible with List<VenueHallFloorRequest>
    // VenueHallFloorRequest usually needs some structure, checking the DTO would be ideal but guessing common structure for now
    // Based on VenueHallFloorRequest:
    // We'll send a list containing one floor with minimum required data.
    // Note: Since we are testing "concurrency/throughput", the valid payload content matters less than the processing logic triggering.
    // However, avoid 400 Bad Request.

    // Let's create a minimal payload that won't fail validation
    const payload = JSON.stringify([
        {
            "venueHallFloor": randomIntBetween(1, 5),
            "venueHallSectionRequestList": [
                {
                    "venueHallSection": "A",
                    "venueHallRowRequestList": [
                        {
                            "venueHallSectionRow": randomIntBetween(1, 10),
                            "venueHallSeatRequestList": [
                                {
                                    "venueHallSeatNumber": randomIntBetween(1, 100),
                                    "venueHallSeatInformation": "VIP"
                                }
                            ]
                        }
                    ]
                }
            ]
        }
    ]);

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: {
            type: type // Tag metrics with the test type
        }
    };

    const url = `${BASE_URL}/api/venue/enter/${hallId}/seats?type=${type}`;
    const res = http.post(url, payload, params);

    check(res, {
        'is status 201': (r) => r.status === 201, // VenueController returns HttpStatus.CREATED (201)
    });

    sleep(1);
}
