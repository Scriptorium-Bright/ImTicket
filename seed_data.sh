#!/bin/bash

# Base URL
URL="http://localhost:10080/api"

echo "🌱 Seeding Data for Load Test..."

# 0. Dummy Image Creation
echo "[0/6] Creating dummy image..."
echo "dummy image" > dummy.jpg

# 1. Venue & Hall Creation
echo "[1/6] Creating Venue & Hall..."
# Venue + Hall (Capacity 100)
curl -X POST "$URL/venue/enter" \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "name": "KSPO Dome",
      "address": "Seoul",
      "phoneNumber": "02-123-4567"
    },
    "venueHallRequest": [
      {
        "name": "Hall 1",
        "totalSeats": 100
      }
    ]
  }'
echo -e "\nVenue Created."

# 2. Seat Template Creation (Async Trigger for Hall 1)
# Assuming Hall ID 1 from previous step
echo "[2/6] Creating Seat Templates (Structure)..."
curl -X POST "$URL/venue/enter/1/seats?type=async" \
  -H "Content-Type: application/json" \
  -d '[
  {
    "venueHallFloor": 1,
    "venueHallSectionRequestList": [
      {
        "venueHallSection": "A",
        "venueHallRowRequestList": [
          {
            "venueHallSectionRow": 1,
            "venueHallSeatRequestList": [
              {
                "venueHallSeatInformation": "VIP",
                "startSeatNumber": 1,
                "endSeatNumber": 50
              }
            ]
          }
        ]
      }
    ]
  }
]'
echo -e "\nSeat Templates Created."

# 3. Performance Creation
echo "[3/6] Creating Performance..."
# Need to use multipart/form-data
# Returns Location Header, but we assume ID 1
curl -X POST "$URL/performance/enter" \
  -F "image=@dummy.jpg;type=image/jpeg" \
  -F "details={
    \"title\": \"IU Love Poem\",
    \"description\": \"IU Concert\",
    \"age\": 15,
    \"startDate\": \"2026-05-01\",
    \"endDate\": \"2026-05-03\",
    \"venueType\": \"CONCERT\"
  };type=application/json"

echo -e "\nPerformance Created."

# 4. Performance Time Creation
echo "[4/6] Creating Performance Time..."
# For Performance ID 1
curl -X POST "$URL/time/enter/1/times" \
  -H "Content-Type: application/json" \
  -d '[
    {
        "startTime": "2026-05-01T19:00:00"
    }
]'
echo -e "\nPerformance Time Created."

# 5. Seat Price Creation
echo "[5/6] Creating Seat Prices..."
# For Performance ID 1
curl -X POST "$URL/price/enter/1/prices" \
  -H "Content-Type: application/json" \
  -d '[
    {
        "seatInfo": "VIP",
        "price": 150000
    }
]'
echo -e "\nSeat Prices Created."

# 6. Seat Instance Generation (The Heavy Task)
echo "[6/6] Generating Seat Instances (Async)..."
# For PerformanceTime ID 1 (created in step 4)
curl -X POST "$URL/seats/1"

echo -e "\n✅ Data Seeding Completed!"
echo "Now you can run k6 tests: k6 run load-test-cache.js"
