"use client"

import * as React from "react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

interface Seat {
    id: string
    row: string
    col: number
    status: "available" | "reserved" | "selected" | "vip"
    price: number
}

interface SeatMapProps {
    onSeatSelect: (seat: Seat) => void
    selectedSeats: Seat[]
}

export function SeatMap({ onSeatSelect, selectedSeats }: SeatMapProps) {
    // Mock seat data generation
    const rows = ["A", "B", "C", "D", "E", "F", "G", "H"]
    const cols = 12

    const generateSeats = () => {
        const seats: Seat[] = []
        rows.forEach((row, rowIndex) => {
            for (let col = 1; col <= cols; col++) {
                // Randomly mark some as reserved for demo
                const isReserved = Math.random() < 0.1
                const isVip = rowIndex < 2 // First 2 rows are VIP

                seats.push({
                    id: `${row}${col}`,
                    row,
                    col,
                    status: isReserved ? "reserved" : isVip ? "vip" : "available",
                    price: isVip ? 0.08 : 0.05
                })
            }
        })
        return seats
    }

    // Use state to hold seats so they don't regenerate on every render
    const [seats] = React.useState<Seat[]>(generateSeats())

    const getSeatColor = (seat: Seat) => {
        if (selectedSeats.find(s => s.id === seat.id)) return "bg-primary text-white border-primary shadow-[0_0_10px_rgba(124,58,237,0.5)]"
        if (seat.status === "reserved") return "bg-muted text-muted-foreground cursor-not-allowed opacity-50"
        if (seat.status === "vip") return "bg-purple-500/20 border-purple-500/50 text-purple-200 hover:bg-purple-500/40"
        return "bg-secondary/50 hover:bg-secondary border-white/10"
    }

    return (
        <div className="w-full overflow-x-auto pb-12">
            {/* Stage */}
            <div className="w-3/4 mx-auto mb-16 relative">
                <div className="h-12 bg-gradient-to-b from-primary/20 to-transparent rounded-t-[50%] border-t border-primary/30 flex items-center justify-center text-primary/50 font-bold tracking-[0.5em] text-sm uppercase shadow-[0_-10px_20px_rgba(124,58,237,0.1)]">
                    STAGE (무대)
                </div>
            </div>

            {/* Seats Grid */}
            <div className="flex flex-col gap-3 items-center min-w-[600px]">
                {rows.map((row) => (
                    <div key={row} className="flex gap-3 items-center">
                        <span className="w-6 text-center text-xs text-muted-foreground font-medium">{row}</span>
                        <div className="flex gap-2">
                            {seats.filter(s => s.row === row).map((seat) => (
                                <button
                                    key={seat.id}
                                    disabled={seat.status === "reserved"}
                                    onClick={() => onSeatSelect(seat)}
                                    className={cn(
                                        "w-8 h-8 rounded-t-lg rounded-b-md text-[10px] font-medium transition-all duration-200 border flex items-center justify-center",
                                        getSeatColor(seat)
                                    )}
                                >
                                    {seat.col}
                                </button>
                            ))}
                        </div>
                        <span className="w-6 text-center text-xs text-muted-foreground font-medium">{row}</span>
                    </div>
                ))}
            </div>

            {/* Legend */}
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
