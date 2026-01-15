import axios from 'axios';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '';

const api = axios.create({
    baseURL: API_BASE_URL,
    headers: {
        'Content-Type': 'application/json',
    },
});

// Add a request interceptor to include the JWT token if available
api.interceptors.request.use(
    (config) => {
        const token = typeof window !== 'undefined' ? localStorage.getItem('accessToken') : null;
        if (token) {
            config.headers.Authorization = `Bearer ${token}`;
        }
        return config;
    },
    (error) => Promise.reject(error)
);

export const authApi = {
    getNonce: (walletAddress: string) => api.get(`/api/user/nonce?walletAddress=${walletAddress}`),
    verifySignature: (walletAddress: string, signature: string) =>
        api.post('/api/user/signature/verify', { walletAddress, signature }),
    sendSms: (to: string) => api.post('/api/sms/certificate', { to }),
    verifySms: (to: string, code: string) => api.post('/api/sms/verify', { to, code }),
};

export const memberApi = {
    register: (data: any) => api.post('/api/user/register', data),
    validate: (walletAddress: string) => api.get(`/api/user/validate/${walletAddress}`),
    changeNickname: (nickname: string) => api.put(`/api/user/myPage/nickname?nickname=${nickname}`),
    getNickname: (walletAddress: string) => api.get(`/api/user/nickname?walletAddress=${walletAddress}`),
    getWalletAddress: (nickname: string) => api.get(`/api/user/walletAddress?nickname=${nickname}`),
};

export const performanceApi = {
    getAll: () => api.get('/api/performance/intro'),
    getDetail: (id: string) => api.get(`/api/performance/intro/${id}`),
    create: (data: FormData) => api.post('/api/performance/enter', data, {
        headers: { 'Content-Type': 'multipart/form-data' }
    }),
};

export const venueApi = {
    create: (data: any) => api.post('/api/venue/enter', data),
    getHalls: () => api.get('/api/venue/halls'),
    initializeSeats: (hallId: string, data: any) => api.post(`/api/venue/enter/${hallId}/seats`, data),
};

export const nftApi = {
    buyTicket: (walletAddress: string) => api.post('/api/nft/ticket/buy', { to: walletAddress }),
};

export const seatApi = {
    getSeats: (performanceTimeId: string) => api.get(`/api/seats/${performanceTimeId}`),
    preReserve: (data: any) => api.post('/api/reservation/pre-reserve', data),
    registerSeats: (performanceTimeId: number) => api.post(`/api/seats/${performanceTimeId}`),
};

export const groupApi = {
    getGroup: (memberAddress: string) => api.get(`/api/group?memberAddress=${memberAddress}`),
    createGroup: (memberAddress: string) => api.post('/api/group', { memberAddress }),
    leaveGroup: (memberAddress: string) => api.post('/api/group/leave', { memberAddress }),
    getInvites: (memberAddress: string) => api.get(`/api/group/invite?memberAddress=${memberAddress}`),
    inviteUser: (from: string, to: string) => api.post('/api/group/invite', { from, to }),
    acceptInvite: (groupAddress: string, memberAddress: string) => api.post('/api/group/invite/accept', { groupAddress, memberAddress }),
    rejectInvite: (groupAddress: string, memberAddress: string) => api.post('/api/group/invite/reject', { groupAddress, memberAddress }),
    allowTicket: (from: string, to: string, tokenId: string) => api.post('/api/group/ticket/allow', { from, to, tokenId }),
    disallowTicket: (from: string, to: string, tokenId: string) => api.post('/api/group/ticket/disallow', { from, to, tokenId }),
};

export const performanceTimeApi = {
    create: (performanceId: string, data: any[]) => api.post(`/api/time/enter/${performanceId}/times`, data),
};

export const seatPriceApi = {
    create: (performanceId: string, data: any[]) => api.post(`/api/price/enter/${performanceId}/prices`, data),
};



export const gatewayTicketApi = {
    buy: (from: string, to: string, details: any) => api.post('/api/ticket/buy', { from, to, details }),
    getTickets: (memberAddress: string) => api.get(`/api/ticket?memberAddress=${memberAddress}`),
    use: (memberAddress: string, tokenId: string) => api.post('/api/ticket/use', { memberAddress, tokenId }),
    share: (memberAddress: string, tokenId: string) => api.post('/api/ticket/share', { memberAddress, tokenId }),
    cancelShare: (memberAddress: string, tokenId: string) => api.post('/api/ticket/cancelShare', { memberAddress, tokenId }),
    burn: (issuerAddress: string, tokenId: string) => api.post('/api/ticket/burn', { issuerAddress, tokenId }),
};

export const entryApi = {
    getToken: (reservationId: string) => api.get(`/api/entry/token/${reservationId}`),
    verify: (token: string, gateName?: string) => api.post('/api/entry/verify', { token, gateName }),
};

export default api;
