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

// Add a response interceptor to unwrap the common ApiResponse format
api.interceptors.response.use(
    (response) => {
        // 백엔드에서 내려주는 공통 응답 포맷 (ApiResponse: { success, data, error })
        // 만약 응답이 이런 구조를 띠고 있고 success가 true라면 data.data를 바로 반환한다.
        if (response.data && typeof response.data === 'object' && 'success' in response.data) {
            if (response.data.success) {
                return { ...response, data: response.data.data };
            } else {
                return Promise.reject(response.data.error || new Error('Unknown API error'));
            }
        }
        return response;
    },
    (error) => {
        if (error.response && error.response.data && error.response.data.error) {
            return Promise.reject(error.response.data.error);
        }
        return Promise.reject(error);
    }
);

export const authApi = {
    getNonce: (walletAddress: string, purpose: 'LOGIN' | 'REGISTER' = 'LOGIN') =>
        api.get('/api/user/nonce', { params: { walletAddress, purpose } }),
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

export const waitingRoomEntryPassStorageKey = (performanceTimeId: string) =>
    `waiting-room:entry-pass:${performanceTimeId}`;

export const storeWaitingRoomEntryPass = (performanceTimeId: string, entryPass: string) => {
    if (typeof window !== 'undefined') {
        sessionStorage.setItem(waitingRoomEntryPassStorageKey(performanceTimeId), entryPass);
    }
};

export const clearWaitingRoomEntryPass = (performanceTimeId: string) => {
    if (typeof window !== 'undefined') {
        sessionStorage.removeItem(waitingRoomEntryPassStorageKey(performanceTimeId));
    }
};

export type SeatStatus = 'AVAILABLE' | 'LOCKED' | 'UNAVAILABLE' | 'RESERVED';
export type SeatInfo = 'VIP' | 'R' | 'S' | 'A' | 'B' | 'C';

export interface SeatResponse {
    id: number;
    seatFloor: number;
    seatSection: string;
    seatRow: number;
    seatNumber: number;
    seatType: SeatInfo;
    price: number;
    isReservation: boolean;
    seatStatus: SeatStatus;
}

export interface ReservationRequest {
    performanceTimeId: number;
    seatIds: number[];
}

export interface ReservationCreateResponse {
    id: number;
    totalPrice: number;
    orderUid: string;
    expiredTime: string;
    responses: SeatResponse[];
}

const waitingRoomPassHeader = (performanceTimeId: string) => {
    if (typeof window === 'undefined') {
        return {};
    }
    const entryPass = sessionStorage.getItem(waitingRoomEntryPassStorageKey(performanceTimeId));
    return entryPass ? { 'X-Waiting-Room-Pass': entryPass } : {};
};

export const seatApi = {
    getSeats: (performanceTimeId: string, signal?: AbortSignal) => api.get<SeatResponse[]>(`/api/seats/${performanceTimeId}`, {
        headers: waitingRoomPassHeader(performanceTimeId),
        signal,
    }),
    preReserve: (data: ReservationRequest, idempotencyKey: string = createReservationIdempotencyKey()) =>
        api.post<ReservationCreateResponse>('/api/reservation/pre-reserve', data, {
            headers: {
                'Idempotency-Key': idempotencyKey,
                ...waitingRoomPassHeader(String(data.performanceTimeId)),
            }
        }),
    registerSeats: (performanceTimeId: number) => api.post(`/api/seats/${performanceTimeId}`),
};

export type WaitingRoomTicketStatus =
    | 'WAITING'
    | 'ADMITTED'
    | 'COMPLETED'
    | 'CANCELED'
    | 'EXPIRED';

export interface WaitingRoomStatusResponse {
    ticketId: string;
    status: WaitingRoomTicketStatus;
    position: number | null;
    sequence: number;
    waitingDeadline: string | null;
    entryExpiresAt: string | null;
    entryPass: string | null;
    pollAfterMs: number;
}

export type WaitingRoomSseEventType = 'snapshot' | 'admitted' | 'terminal' | 'keepalive';

export interface WaitingRoomSseEvent {
    type: WaitingRoomSseEventType;
    data: WaitingRoomStatusResponse | { at: string };
}

const consumeWaitingRoomSse = async (
    performanceTimeId: string,
    ticketId: string,
    signal: AbortSignal,
    onEvent: (event: WaitingRoomSseEvent) => void,
) => {
    const token = typeof window !== 'undefined' ? localStorage.getItem('accessToken') : null;
    const response = await fetch(
        `${API_BASE_URL}/api/reservation/waiting-room/${performanceTimeId}/tickets/${ticketId}/events`,
        {
            method: 'GET',
            headers: {
                Accept: 'text/event-stream',
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
            },
            cache: 'no-store',
            signal,
        },
    );
    if (!response.ok || !response.body) {
        throw new Error(`Waiting Room stream failed: ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) {
                return;
            }
            buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '');
            let boundary = buffer.indexOf('\n\n');
            while (boundary >= 0) {
                const frame = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                let type: WaitingRoomSseEventType | null = null;
                const data = frame.split('\n').reduce<string[]>((lines, line) => {
                    if (line.startsWith('event:')) {
                        type = line.slice('event:'.length).trim() as WaitingRoomSseEventType;
                    }
                    if (line.startsWith('data:')) {
                        lines.push(line.slice('data:'.length).trim());
                    }
                    return lines;
                }, []);
                if (type && data.length > 0) {
                    onEvent({ type, data: JSON.parse(data.join('\n')) });
                }
                boundary = buffer.indexOf('\n\n');
            }
        }
    } finally {
        reader.releaseLock();
    }
};

export const waitingRoomApi = {
    join: (performanceTimeId: string) =>
        api.post<WaitingRoomStatusResponse>(`/api/reservation/waiting-room/${performanceTimeId}/join`),
    status: (performanceTimeId: string, ticketId: string) =>
        api.get<WaitingRoomStatusResponse>(`/api/reservation/waiting-room/${performanceTimeId}/tickets/${ticketId}`),
    events: consumeWaitingRoomSse,
    cancel: (performanceTimeId: string, ticketId: string) =>
        api.post<WaitingRoomStatusResponse>(`/api/reservation/waiting-room/${performanceTimeId}/tickets/${ticketId}/cancel`),
};

export const createReservationIdempotencyKey = (): string => {
    if (typeof globalThis.crypto?.randomUUID === 'function') {
        return globalThis.crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
        const random = Math.floor(Math.random() * 16);
        const value = character === 'x' ? random : (random & 0x3) | 0x8;
        return value.toString(16);
    });
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
