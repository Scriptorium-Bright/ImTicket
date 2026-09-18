'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import type { SeatResponse } from '@/services/api'

export interface Seat {
    id: string
    backendId: number
    row: string
    col: number
    status: 'available' | 'reserved' | 'selected' | 'vip'
    price: number
    seatType: SeatResponse['seatType']
    seatStatus: SeatResponse['seatStatus']
}

export interface SeatMapProps {
    seats: Seat[]
    onSeatSelect: (seat: Seat) => void
    selectedSeats: Seat[]
    isLoading?: boolean
    error?: string | null
}

export const toSeatView = (seat: SeatResponse): Seat => {
    const isAvailable = seat.seatStatus === 'AVAILABLE'

    return {
        id: String(seat.id),
        backendId: seat.id,
        row: `${seat.seatSection}-${seat.seatRow}`,
        col: seat.seatNumber,
        status: isAvailable ? (seat.seatType === 'VIP' ? 'vip' : 'available') : 'reserved',
        price: seat.price,
        seatType: seat.seatType,
        seatStatus: seat.seatStatus,
    }
}

export function SeatMap({ seats, onSeatSelect, selectedSeats, isLoading = false, error = null }: SeatMapProps) {
    const rows = React.useMemo(() => {
        const grouped = new Map<string, Seat[]>()

        seats.forEach((seat) => {
            const rowSeats = grouped.get(seat.row) ?? []
            rowSeats.push(seat)
            grouped.set(seat.row, rowSeats)
        })

        return Array.from(grouped.entries())
            .sort(([left], [right]) => left.localeCompare(right, undefined, { numeric: true }))
            .map(([label, rowSeats]) => [
                label,
                rowSeats.sort((left, right) => left.col - right.col),
            ] as const)
    }, [seats])

    const getSeatColor = (seat: Seat) => {
        if (selectedSeats.some((selectedSeat) => selectedSeat.id === seat.id)) {
            return 'bg-primary text-white border-primary shadow-[0_0_10px_rgba(124,58,237,0.5)]'
        }
        if (seat.status === 'reserved') {
            return 'bg-muted text-muted-foreground cursor-not-allowed opacity-50'
        }
        if (seat.status === 'vip') {
            return 'bg-purple-500/20 border-purple-500/50 text-purple-200 hover:bg-purple-500/40'
        }
        return 'bg-secondary/50 hover:bg-secondary border-white/10'
    }

    if (isLoading) {
        return <div className="py-20 text-center text-muted-foreground">좌석 정보를 불러오는 중입니다.</div>
    }

    if (error) {
        return <div className="py-20 text-center text-red-300">{error}</div>
    }

    if (seats.length === 0) {
        return <div className="py-20 text-center text-muted-foreground">등록된 좌석이 없습니다.</div>
    }

    return (
        <div className="w-full overflow-x-auto pb-12">
            <div className="w-3/4 mx-auto mb-16 relative">
                <div className="h-12 bg-gradient-to-b from-primary/20 to-transparent rounded-t-[50%] border-t border-primary/30 flex items-center justify-center text-primary/50 font-bold tracking-[0.5em] text-sm uppercase shadow-[0_-10px_20px_rgba(124,58,237,0.1)]">
                    STAGE (무대)
                </div>
            </div>

            <div className="flex flex-col gap-3 items-center min-w-[600px]">
                {rows.map(([row, rowSeats]) => (
                    <div key={row} className="flex gap-3 items-center">
                        <span className="w-16 text-center text-xs text-muted-foreground font-medium">{row}</span>
                        <div className="flex gap-2">
                            {rowSeats.map((seat) => (
                                <button
                                    key={seat.id}
                                    type="button"
                                    disabled={seat.status === 'reserved'}
                                    aria-label={`${row} ${seat.col}번 좌석 ${seat.seatStatus}`}
                                    onClick={() => onSeatSelect(seat)}
                                    className={cn(
                                        'w-8 h-8 rounded-t-lg rounded-b-md text-[10px] font-medium transition-all duration-200 border flex items-center justify-center',
                                        getSeatColor(seat),
                                    )}
                                >
                                    {seat.col}
                                </button>
                            ))}
                        </div>
                        <span className="w-16 text-center text-xs text-muted-foreground font-medium">{row}</span>
                    </div>
                ))}
            </div>

            <div className="flex justify-center gap-6 mt-12 text-sm text-muted-foreground">
                <div className="flex items-center gap-2">
                    <div className="w-4 h-4 rounded bg-secondary/50 border border-white/10" />
                    <span>예매 가능</span>
                </div>
                <div className="flex items-center gap-2">
                    <div className="w-4 h-4 rounded bg-purple-500/20 border border-purple-500/50" />
                    <span>VIP석</span>
                </div>
                <div className="flex items-center gap-2">
                    <div className="w-4 h-4 rounded bg-primary border border-primary" />
                    <span>선택됨</span>
                </div>
                <div className="flex items-center gap-2">
                    <div className="w-4 h-4 rounded bg-muted opacity-50" />
                    <span>예매 불가</span>
                </div>
            </div>
        </div>
    )
}
