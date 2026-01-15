import axios from 'axios';
import FormData from 'form-data';
import fs from 'fs';

const API_URL = 'http://localhost:8080';

// Helper to create FormData
const createFormData = (data: Record<string, any>) => {
    const formData = new FormData();
    Object.entries(data).forEach(([key, value]) => {
        formData.append(key, value);
    });
    return formData;
};

async function seed() {
    console.log('🌱 Starting data seeding...');

    try {
        // 1. Create Venue
        console.log('Creating Venue...');
        const venueRes = await axios.post(`${API_URL}/api/venue/enter`, {
            name: "Seoul Arts Center",
            address: "2406 Nambusunhwan-ro, Seocho-gu, Seoul",
            totalSeat: 1000
        });
        const venueId = venueRes.data; // Assuming it returns ID
        console.log(`Venue created: ${venueId}`);

        // 2. Create Hall (Assuming backend creates a default hall or we need to fetch it)
        // Let's fetch halls to get the ID
        const hallsRes = await axios.get(`${API_URL}/api/venue/halls`);
        const hall = hallsRes.data.find((h: any) => h.name === "Seoul Arts Center"); // Or however it's named
        const hallId = hall ? hall.id : 1; // Fallback
        console.log(`Using Hall ID: ${hallId}`);

        // 3. Create Performance
        console.log('Creating Performance...');
        const perfFormData = new FormData();
        perfFormData.append('title', 'The Phantom of the Opera');
        perfFormData.append('description', 'The classic musical by Andrew Lloyd Webber');
        perfFormData.append('genre', 'Musical');
        perfFormData.append('runningTime', '150');
        perfFormData.append('intermission', '20');
        perfFormData.append('ageRating', '8');
        perfFormData.append('posterImage', 'https://example.com/poster.jpg'); // Mock image
        perfFormData.append('hallId', hallId);

        const perfRes = await axios.post(`${API_URL}/api/performance/enter`, perfFormData, {
            headers: { ...perfFormData.getHeaders() }
        });
        const performanceId = perfRes.data; // Assuming ID
        console.log(`Performance created: ${performanceId}`);

        // 4. Create Performance Time
        console.log('Creating Performance Time...');
        const timeRes = await axios.post(`${API_URL}/api/time/enter/${performanceId}/times`, [
            { date: "2025-12-25", time: "19:00" },
            { date: "2025-12-26", time: "19:00" }
        ]);
        console.log('Performance Times created');

        // 5. Create Seat Prices
        console.log('Creating Seat Prices...');
        await axios.post(`${API_URL}/api/price/enter/${performanceId}/prices`, [
            { grade: "VIP", price: 150000 },
            { grade: "R", price: 120000 },
            { grade: "S", price: 90000 }
        ]);
        console.log('Seat Prices created');

        // 6. Initialize Seats (This might be complex, usually done via Organizer UI which calls seatApi.initializeSeats)
        // We can try to call it if we have the time IDs.
        // But for now, basic data is enough.

        console.log('✅ Seeding completed successfully!');

    } catch (error: any) {
        console.error('❌ Seeding failed:', error.response ? error.response.data : error.message);
    }
}

seed();
