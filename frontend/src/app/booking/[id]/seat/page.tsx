"use client"

import * as React from "react"
import { Navbar } from "@/components/layout/navbar"
import { SeatMap } from "@/components/booking/seat-map"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { ArrowLeft, Timer, CreditCard } from "lucide-react"
import { useRouter } from "next/navigation"
import { nftApi, gatewayTicketApi } from "@/services/api"
import { useUserStore } from "@/store/useUserStore"

interface Seat {
    id: string
    row: string
    col: number
    status: "available" | "reserved" | "selected" | "vip"
    price: number
}

export default function SeatSelectionPage() {
    const router = useRouter()
    const { walletAddress } = useUserStore()
    const [isBooking, setIsBooking] = React.useState(false)
    const [selectedSeats, setSelectedSeats] = React.useState<Seat[]>([])
    const params = { id: "1" } // Mock params for now since we are not using real routing params yet

    const handleSeatSelect = (seat: Seat) => {
        if (selectedSeats.find(s => s.id === seat.id)) {
            setSelectedSeats(selectedSeats.filter(s => s.id !== seat.id))
        } else {
            if (selectedSeats.length >= 4) {
                alert("최대 4개 좌석까지 선택 가능합니다.")
                return
            }
            setSelectedSeats([...selectedSeats, seat])
        }
    }

    const handleBooking = async () => {
        if (selectedSeats.length === 0) {
            alert("좌석을 선택해주세요.")
            return
        }
        if (!walletAddress) {
            alert("지갑을 연결해주세요.")
            return
        }

        setIsBooking(true)
        try {
            // For the mockup, we'll mint a ticket for the first selected seat
            // In a real app, we'd loop or batch mint
            const seat = selectedSeats[0]
            const details = {
                performanceId: params.id,
                seatId: seat,
                price: 150000, // Mock price
                date: new Date().toISOString()
            }

            // Use Gateway API directly for full IPFS + Minting flow
            // 'from' is the organizer (mocked for now), 'to' is the user
            const organizerAddress = "0x409E0826FE9E332617B8c38C0580aa93cBfaB0c6" // Gateway Signer Address

            await gatewayTicketApi.buy(organizerAddress, walletAddress, details)

            alert("예매가 완료되었습니다! (NFT 티켓 발급 완료)")
            router.push("/mypage")
        } catch (error) {
            console.error(error)
            alert("예매 처리에 실패했습니다.")
        } finally {
            setIsBooking(false)
        }
    }

    const totalPrice = selectedSeats.reduce((sum, seat) => sum + seat.price, 0)

    return (
        <main className="min-h-screen bg-background pb-20">
            <Navbar />

            <div className="container mx-auto px-4 pt-24">
                <Button
                    variant="ghost"
                    className="mb-6 gap-2 pl-0 hover:bg-transparent hover:text-primary"
                    onClick={() => router.back()}
                >
                    <ArrowLeft className="w-4 h-4" /> 공연 정보로 돌아가기
                </Button>

                <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
                    {/* Left: Seat Map */}
                    <div className="lg:col-span-2">
                        <Card className="border-white/5 bg-black/20 backdrop-blur-xl">
                            <CardHeader>
                                <CardTitle>좌석 선택</CardTitle>
                                <p className="text-muted-foreground text-sm">
                                    원하시는 좌석을 선택해주세요. 화면 상단이 무대 방향입니다.
                                </p>
                            </CardHeader>
                            <CardContent>
                                <SeatMap
                                    onSeatSelect={handleSeatSelect}
                                    selectedSeats={selectedSeats}
                                />
                            </CardContent>
                        </Card>
                    </div>

                    {/* Right: Summary */}
                    <div className="lg:col-span-1">
                        <div className="sticky top-24 space-y-4">
                            {/* Timer Card (Mock) */}
                            <div className="bg-orange-500/10 border border-orange-500/20 rounded-lg p-4 flex items-center justify-center gap-2 text-orange-400">
                                <Timer className="w-4 h-4" />
                                <span className="font-mono font-bold">09:59</span>
                                <span className="text-sm">남은 시간</span>
                            </div>

                            <Card className="border-primary/20 shadow-lg shadow-primary/5">
                                <CardHeader>
                                    <CardTitle>예매 정보</CardTitle>
                                </CardHeader>
                                <CardContent className="space-y-4">
                                    <div>
                                        <h3 className="font-medium">네온 드림 콘서트</h3>
                                        <p className="text-sm text-muted-foreground">2024. 05. 20 • 19:00</p>
                                        <p className="text-sm text-muted-foreground">예술의 전당</p>
                                    </div>

                                    <div className="border-t border-white/10 pt-4 space-y-2">
                                        {selectedSeats.length === 0 ? (
                                            <p className="text-sm text-muted-foreground text-center py-4 italic">
                                                선택된 좌석이 없습니다
                                            </p>
                                        ) : (
                                            selectedSeats.map((seat) => (
                                                <div key={seat.id} className="flex justify-between text-sm">
                                                    <span>좌석 {seat.row}-{seat.col}</span>
                                                    <span>{seat.price} ETH</span>
                                                </div>
                                            ))
                                        )}
                                    </div>

                                    <div className="border-t border-white/10 pt-4 flex justify-between items-center font-bold text-lg">
                                        <span>총 결제 금액</span>
                                        <span className="text-primary">{totalPrice.toFixed(2)} ETH</span>
                                    </div>
                                </CardContent>
                                <CardFooter>
                                    <Button
                                        className="w-full gap-2"
                                        variant="gradient"
                                        size="lg"
                                        disabled={selectedSeats.length === 0 || isBooking}
                                        onClick={handleBooking}
                                    >
                                        <CreditCard className="w-4 h-4" />
                                        {isBooking ? "처리 중..." : "예매 하기"}
                                    </Button>
                                </CardFooter>
                            </Card>
                        </div>
                    </div>
                </div>
            </div>
        </main>
    )
}
