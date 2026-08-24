"use client"

import * as React from "react"
import Link from "next/link"
import { useParams, useRouter } from "next/navigation"
import { ArrowLeft, CheckCircle2, Clock3, Loader2, Users, XCircle } from "lucide-react"
import { Navbar } from "@/components/layout/navbar"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
    clearWaitingRoomEntryPass,
    storeWaitingRoomEntryPass,
    waitingRoomApi,
    type WaitingRoomStatusResponse,
} from "@/services/api"
import { useUserStore } from "@/store/useUserStore"

const statusLabel: Record<WaitingRoomStatusResponse["status"], string> = {
    WAITING: "입장 대기 중",
    ADMITTED: "입장 가능",
    COMPLETED: "입장 완료",
    CANCELED: "대기 취소",
    EXPIRED: "대기 만료",
}

export default function WaitingRoomPage() {
    const params = useParams<{ id: string }>()
    const router = useRouter()
    const performanceTimeId = String(params.id)
    const { isLoggedIn } = useUserStore()
    const [ticket, setTicket] = React.useState<WaitingRoomStatusResponse | null>(null)
    const [error, setError] = React.useState<string | null>(null)
    const [isLoading, setIsLoading] = React.useState(false)
    const abortControllerRef = React.useRef<AbortController | null>(null)
    const retryTimerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)
    const reconciliationTimerRef = React.useRef<ReturnType<typeof setInterval> | null>(null)

    const stopEventStream = React.useCallback(() => {
        abortControllerRef.current?.abort()
        abortControllerRef.current = null
        if (retryTimerRef.current) {
            clearTimeout(retryTimerRef.current)
            retryTimerRef.current = null
        }
        if (reconciliationTimerRef.current) {
            clearInterval(reconciliationTimerRef.current)
            reconciliationTimerRef.current = null
        }
    }, [])

    const applyTicket = React.useCallback((nextTicket: WaitingRoomStatusResponse) => {
        setTicket(nextTicket)
        setIsLoading(false)
        setError(null)
        if (nextTicket.status === "ADMITTED" && nextTicket.entryPass) {
            storeWaitingRoomEntryPass(performanceTimeId, nextTicket.entryPass)
        }
        if (nextTicket.status === "CANCELED" || nextTicket.status === "COMPLETED" || nextTicket.status === "EXPIRED") {
            clearWaitingRoomEntryPass(performanceTimeId)
        }
    }, [performanceTimeId])

    const startEventStream = React.useCallback((ticketId: string) => {
        stopEventStream()
        let failureCount = 0

        const reconcileStatus = async () => {
            try {
                const response = await waitingRoomApi.status(performanceTimeId, ticketId)
                const recoveredTicket = response.data
                applyTicket(recoveredTicket)
                if (recoveredTicket.status !== "WAITING") {
                    stopEventStream()
                }
            } catch {
                // SSE 연결은 유지하고 다음 재조정 주기에 다시 상태를 확인한다.
            }
        }

        reconciliationTimerRef.current = setInterval(() => {
            void reconcileStatus()
        }, 15_000)

        const connect = async () => {
            const controller = new AbortController()
            abortControllerRef.current = controller
            try {
                await waitingRoomApi.events(performanceTimeId, ticketId, controller.signal, (event) => {
                    if (event.type === "keepalive") {
                        return
                    }
                    const nextTicket = event.data as WaitingRoomStatusResponse
                    applyTicket(nextTicket)
                    if (nextTicket.status === "ADMITTED") {
                        stopEventStream()
                        return
                    }
                    if (nextTicket.status === "CANCELED" || nextTicket.status === "COMPLETED" || nextTicket.status === "EXPIRED") {
                        stopEventStream()
                    }
                })
                if (controller.signal.aborted) {
                    return
                }
                throw new Error("Waiting Room stream closed")
            } catch {
                if (controller.signal.aborted) {
                    return
                }
                failureCount += 1
                if (failureCount % 3 === 0) {
                    try {
                        const response = await waitingRoomApi.status(performanceTimeId, ticketId)
                        const recoveredTicket = response.data
                        applyTicket(recoveredTicket)
                        if (recoveredTicket.status !== "WAITING") {
                            return
                        }
                    } catch {
                        setError("대기열 연결을 복구하는 중입니다.")
                    }
                }
                const upperBoundMs = Math.min(30_000, 1_000 * (2 ** Math.min(failureCount - 1, 5)))
                const retryDelayMs = Math.floor(Math.random() * upperBoundMs)
                retryTimerRef.current = setTimeout(() => {
                    void connect()
                }, retryDelayMs)
            }
        }

        void connect()
    }, [applyTicket, performanceTimeId, stopEventStream])

    const joinWaitingRoom = React.useCallback(async () => {
        if (!isLoggedIn) return

        setIsLoading(true)
        setError(null)
        clearWaitingRoomEntryPass(performanceTimeId)
        try {
            const response = await waitingRoomApi.join(performanceTimeId)
            const joinedTicket = response.data
            applyTicket(joinedTicket)
            startEventStream(joinedTicket.ticketId)
        } catch {
            setError("Waiting Room 입장에 실패했습니다. 로그인 상태를 확인해주세요.")
        } finally {
            setIsLoading(false)
        }
    }, [applyTicket, isLoggedIn, performanceTimeId, startEventStream])

    React.useEffect(() => {
        void joinWaitingRoom()
        return stopEventStream
    }, [joinWaitingRoom, stopEventStream])

    const handleCancel = async () => {
        if (!ticket) return
        stopEventStream()
        try {
            const response = await waitingRoomApi.cancel(performanceTimeId, ticket.ticketId)
            applyTicket(response.data)
        } catch {
            setError("대기 취소에 실패했습니다.")
        }
    }

    const isTerminal = ticket && ticket.status !== "WAITING" && ticket.status !== "ADMITTED"

    return (
        <main className="min-h-screen bg-background">
            <Navbar />
            <div className="container mx-auto flex min-h-screen max-w-2xl items-center px-4 py-24">
                <Card className="w-full border-primary/20 shadow-lg shadow-primary/10">
                    <CardHeader className="text-center">
                        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 text-primary">
                            {ticket?.status === "ADMITTED" ? <CheckCircle2 className="h-8 w-8" /> : <Users className="h-8 w-8" />}
                        </div>
                        <CardTitle className="text-3xl">Waiting Room</CardTitle>
                        <p className="text-sm text-muted-foreground">공연 입장을 준비하고 있습니다.</p>
                    </CardHeader>

                    <CardContent className="space-y-6">
                        {!isLoggedIn && (
                            <div className="rounded-lg border border-orange-500/30 bg-orange-500/10 p-4 text-center text-sm text-orange-200">
                                먼저 지갑을 연결해주세요.
                            </div>
                        )}

                        {isLoading && (
                            <div className="flex items-center justify-center gap-2 text-muted-foreground">
                                <Loader2 className="h-4 w-4 animate-spin" /> 대기열에 입장하는 중...
                            </div>
                        )}

                        {error && <p className="text-center text-sm text-red-300">{error}</p>}

                        {ticket && (
                            <>
                                <div className="rounded-xl border border-white/10 bg-white/5 p-6 text-center">
                                    <p className="text-sm text-muted-foreground">현재 상태</p>
                                    <p className="mt-2 text-2xl font-semibold text-primary">{statusLabel[ticket.status]}</p>

                                    {ticket.status === "WAITING" && (
                                        <div className="mt-6 grid grid-cols-2 gap-4">
                                            <div className="rounded-lg bg-black/20 p-4">
                                                <Users className="mx-auto mb-2 h-5 w-5 text-primary" />
                                                <p className="text-xs text-muted-foreground">현재 대기 순번</p>
                                                <p className="mt-1 text-2xl font-bold">{ticket.position ?? "-"}</p>
                                            </div>
                                            <div className="rounded-lg bg-black/20 p-4">
                                                <Clock3 className="mx-auto mb-2 h-5 w-5 text-primary" />
                                                <p className="text-xs text-muted-foreground">입장 알림</p>
                                                <p className="mt-1 text-sm font-bold">자동 안내</p>
                                            </div>
                                        </div>
                                    )}

                                    {ticket.status === "ADMITTED" && (
                                        <div className="mt-6 space-y-4">
                                            <p className="text-sm text-muted-foreground">입장이 허용되었습니다. 좌석 선택을 진행해주세요.</p>
                                            <Link href={`/booking/${performanceTimeId}/seat`} className="block">
                                                <Button className="w-full" variant="gradient">좌석 선택으로 이동</Button>
                                            </Link>
                                        </div>
                                    )}

                                    {isTerminal && (
                                        <div className="mt-6 flex items-center justify-center gap-2 text-sm text-muted-foreground">
                                            <XCircle className="h-4 w-4" /> 이 대기 티켓은 더 사용할 수 없습니다.
                                        </div>
                                    )}
                                </div>

                                {ticket.status === "WAITING" && (
                                    <Button variant="outline" className="w-full" onClick={handleCancel}>
                                        대기 취소
                                    </Button>
                                )}
                            </>
                        )}

                        {!ticket && !isLoading && isLoggedIn && (
                            <Button className="w-full" onClick={() => void joinWaitingRoom()}>
                                다시 입장하기
                            </Button>
                        )}

                        <Button variant="ghost" className="w-full gap-2" onClick={() => router.back()}>
                            <ArrowLeft className="h-4 w-4" /> 이전 페이지
                        </Button>
                    </CardContent>
                </Card>
            </div>
        </main>
    )
}
