
const { ethers } = require('ethers');
const axios = require('axios');
const fs = require('fs');
const path = require('path');

const BACKEND_URL = 'http://127.0.0.1:10080/api';
const GATEWAY_URL = 'http://127.0.0.1:7001/api';

// Use a dummy image from public folder
// If it doesn't exist, Create a simple text file pretending to be an image or use an existing one
const PUBLIC_DIR = path.join(__dirname, 'public');
const IMAGE_PATH = path.join(PUBLIC_DIR, 'next.svg');

// Ensure image exists
if (!fs.existsSync(IMAGE_PATH)) {
    console.warn(`⚠️ Warning: ${IMAGE_PATH} not found. Performance creation might fail if file is required.`);
}

/**
 * Creates a Venue and Hall if none exist.
 * Returns a valid venueHallId.
 */
async function getOrCreateVenueHallId() {
    try {
        const res = await axios.get(`${BACKEND_URL}/venue/halls`);
        if (res.data && res.data.length > 0) {
            console.log(`✅ Using existing Venue Hall ID: ${res.data[0].hallId}`);
            return res.data[0].hallId;
        }

        console.log("ℹ️ No Venue Halls found. Creating new Venue...");
        const venueData = {
            request: {
                name: "IM Art Center",
                address: "Yeongdeungpo-gu, Seoul",
                phoneNumber: "02-1234-5678"
            },
            venueHallRequest: [
                { name: "Grand Hall", totalSeats: 1000 },
                { name: "Small Hall", totalSeats: 300 }
            ]
        };

        await axios.post(`${BACKEND_URL}/venue/enter`, venueData);
        console.log("✅ Created new Venue 'IM Art Center'. fetching ID...");

        // Fetch again
        const res2 = await axios.get(`${BACKEND_URL}/venue/halls`);
        if (res2.data && res2.data.length > 0) {
            return res2.data[0].hallId;
        }
        throw new Error("Failed to retrieve Hall ID after creation.");

    } catch (error) {
        console.error("❌ Failed to get or create Venue:", error.message);
        return null;
    }
}

async function uploadPerformance(performanceData) {
    try {
        const formData = new FormData();
        const jsonBlob = new Blob([JSON.stringify(performanceData)], { type: 'application/json' });
        formData.append('details', jsonBlob);

        if (fs.existsSync(IMAGE_PATH)) {
            const fileBuffer = fs.readFileSync(IMAGE_PATH);
            const fileBlob = new Blob([fileBuffer], { type: 'image/svg+xml' });
            formData.append('image', fileBlob, 'poster.svg');
        } else {
            // Create a dummy blob if file missing
            const fileBlob = new Blob(["dummy payload"], { type: 'text/plain' });
            formData.append('image', fileBlob, 'poster.txt');
        }

        const response = await axios.post(`${BACKEND_URL}/performance/enter`, formData, {
            headers: { 'Content-Type': 'multipart/form-data' }
        });

        if (response.status !== 200 && response.status !== 201) {
            throw new Error(`Status ${response.status}`);
        }

        const location = response.headers['location'] || response.headers['Location'];
        const id = location ? location.split('/').pop() : null;
        console.log(`✅ Created Performance: "${performanceData.title}" (ID: ${id})`);
        return id;
    } catch (error) {
        console.error(`❌ Error creating performance "${performanceData.title}":`, error.message);
        return null;
    }
}

async function createPerformanceTime(performanceId, times) {
    try {
        const res = await axios.post(`${BACKEND_URL}/time/enter/${performanceId}/times`, times);
        console.log(`   Detailed Time allocated for Performance ID ${performanceId}`);
        // Response is list of PerformanceTimeResponse. We need to trigger seat gen for each.
        if (Array.isArray(res.data)) {
            for (const pt of res.data) {
                try {
                    console.log(`     -> Generating Seats for Time ID ${pt.id}...`);
                    await axios.post(`${BACKEND_URL}/seats/${pt.id}`);
                    // console.log(`        Seats generated.`);
                } catch (err) {
                    console.error(`        ❌ Failed to generate seats for Time ID ${pt.id}: ${err.message}`);
                }
            }
        }
    } catch (e) {
        console.error(`   Failed to add time for ${performanceId}: ${e.message}`);
    }
}

async function createSeatPrice(performanceId, prices) {
    try {
        await axios.post(`${BACKEND_URL}/price/enter/${performanceId}/prices`, prices);
        console.log(`   Detailed Prices set for Performance ID ${performanceId}`);
    } catch (e) {
        console.error(`   Failed to add price for ${performanceId}: ${e.message}`);
    }
}

async function createGroup(walletAddress) {
    try {
        const res = await axios.post(`${GATEWAY_URL}/group`, { memberAddress: walletAddress });
        console.log(`✅ Created Group for ${walletAddress}: Address ${res.data.groupAddress}`);
        return res.data.groupAddress;
    } catch (error) {
        if (error.response && error.response.data === 'Already group member') {
            console.log(`⚠️ User ${walletAddress} is already a group member.`);
            return true;
        }
        console.error(`❌ Failed to create group for ${walletAddress}:`, error.message);
        return null;
    }
}


async function registerUser(wallet, index) {
    try {
        const message = "Sign this message to verify your identity.";
        const signature = await wallet.signMessage(message);

        const payload = {
            walletAddress: wallet.address,
            message: message,
            signature: signature,
            phoneNumber: `0100000000${index}`,
            code: "000000",
            nickname: `User_${index}_${Math.floor(Math.random() * 1000)}`
        };

        await axios.post(`${BACKEND_URL}/user/register`, payload);
        console.log(`   ✅ Registered User_${index} (${wallet.address})`);
    } catch (error) {
        if (error.response && error.response.data && error.response.data.message === "Wallet address already registered.") {
            console.log(`   ⚠️ User_${index} already registered.`);
        } else {
            console.error(`   ❌ Failed to register User_${index}:`, error.message);
            if (error.response) console.error(JSON.stringify(error.response.data, null, 2));
        }
    }
}

async function main() {
    console.log("🚀 Starting Data Seeding...");

    // 0. Ensure Venue for Performances
    const hallId = await getOrCreateVenueHallId();
    if (!hallId) {
        console.error("❌ Cannot proceed without a VenueHall ID.");
        return;
    }


    // 1. Generate Wallets for Groups
    const wallets = [];
    console.log(`\n--- 1. Generating 5 Wallets & Groups ---`);
    for (let i = 0; i < 5; i++) {
        const w = ethers.Wallet.createRandom();
        wallets.push(w);
        console.log(`   Subject: ${w.address}`);

        // Register User (Backend)
        await registerUser(w, i + 1);

        // Create Group
        await createGroup(w.address);
    }

    // 2. Create Performances
    console.log(`\n--- 2. Creating Performances ---`);
    const performances = [
        {
            title: "IM CONCERT: WORLD TOUR",
            description: "The greatest world tour of IM artists.",
            age: 12,
            startDate: "2024-05-01",
            endDate: "2024-05-03",
            venueType: "SEOUL"
        },
        {
            title: "Classic Summer Night",
            description: "Experience the breeze of classical music.",
            age: 0,
            startDate: "2024-07-20",
            endDate: "2024-07-20",
            venueType: "BUSAN"
        },
        {
            title: "Code Rock Festival",
            description: "Developers rock the stage!",
            age: 19,
            startDate: "2024-09-10",
            endDate: "2024-09-12",
            venueType: "DAEGU"
        },
        {
            title: "Phantom of the Opera",
            description: "The legendary musical returns.",
            age: 12,
            startDate: "2024-12-01",
            endDate: "2024-12-25",
            venueType: "MUSICAL"
        },
        {
            title: "2025 K-League Opening",
            description: "The start of the 2025 season.",
            age: 0,
            startDate: "2025-03-01",
            endDate: "2025-03-01",
            venueType: "SPORT"
        }
    ];

    for (const p of performances) {
        const pId = await uploadPerformance(p);
        if (pId) {
            // Allocate Times
            const times = [
                { showDate: p.startDate, showTime: "19:00", venueHallId: hallId },
                { showDate: p.startDate, showTime: "14:00", venueHallId: hallId }
            ];
            // Adjust for single day?
            if (p.startDate !== p.endDate) {
                times.push({ showDate: p.endDate, showTime: "18:00", venueHallId: hallId });
            }

            await createPerformanceTime(pId, times);

            // Allocate Prices
            // SeatInfo: VIP, R, S, A, B, C
            const prices = [
                { seatInfo: "VIP", price: 150000 },
                { seatInfo: "R", price: 120000 },
                { seatInfo: "S", price: 90000 }
            ];
            await createSeatPrice(pId, prices);
        }
    }

    console.log("\n✨ Data Seeding Completed!");
    console.log("Start Frontend and check 'Groups' (by connecting one of the wallets) and 'Performances' list.");
    console.log("Generated Wallets (Private Keys):");
    wallets.forEach(w => console.log(`${w.address} : ${w.privateKey}`));
}

main();
