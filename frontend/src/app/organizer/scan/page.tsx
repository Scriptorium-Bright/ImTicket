"use client"

import * as React from "react"
import { Navbar } from "@/components/layout/navbar"
import { Footer } from "@/components/layout/footer"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Scan, CheckCircle2, XCircle, History } from "lucide-react"
import { QrReader } from "react-qr-reader"
import { entryApi } from "@/services/api"
import { cn } from "@/lib/utils"

interface ScanLog {
    time: string
    status: "success" | "error"
    message: string
}

export default function ScannerPage() {
    const [data, setData] = React.useState<string | null>(null)
    const [status, setStatus] = React.useState<"idle" | "processing" | "success" | "error">("idle")
    const [message, setMessage] = React.useState("QR 코드를 스캔해주세요.")
    const [logs, setLogs] = React.useState<ScanLog[]>([])

    const handleScan = async (token: string | null) => {
        if (!token) return
        if (status === "processing" || status === "success") return // Prevent double scan while processing or showing success

        // Simple debounce/throttle could be added here
        if (token === data) return

        setData(token)
        setStatus("processing")
        setMessage("검증 중...")

        try {
            await entryApi.verify(token, "Main Gate")
            setStatus("success")
            setMessage("입장 확인되었습니다.")
            addLog("success", "입장 승인")

            // Reset after 2 seconds
            setTimeout(() => {
                setStatus("idle")
                setData(null)
                setMessage("QR 코드를 스캔해주세요.")
            }, 2000)
        } catch (error: any) {
            setStatus("error")
            const errMsg = error.response?.data?.message || "유효하지 않은 티켓입니다."
            setMessage(errMsg)
            addLog("error", errMsg)

            // Reset after 2 seconds
            setTimeout(() => {
                setStatus("idle")
                setData(null)
                setMessage("QR 코드를 스캔해주세요.")
            }, 2000)
        }
    }

    const addLog = (status: "success" | "error", message: string) => {
        const now = new Date().toLocaleTimeString()
        setLogs(prev => [{ time: now, status, message }, ...prev].slice(0, 10))
    }

    return (
        <main className="min-h-screen bg-background pb-20">
            <Navbar />
            <div className="container mx-auto px-4 pt-32 max-w-md">
                <div className="flex items-center justify-between mb-6">
                    <h1 className="text-2xl font-bold">티켓 스캐너</h1>
                    <div className={cn(
                        "px-3 py-1 rounded-full text-xs font-medium",
                        status === "idle" && "bg-gray-500/20 text-gray-400",
                        status === "processing" && "bg-blue-500/20 text-blue-400",
                        status === "success" && "bg-green-500/20 text-green-400",
                        status === "error" && "bg-red-500/20 text-red-400"
                    )}>
                        {status === "idle" && "대기 중"}
                        {status === "processing" && "처리 중"}
                        {status === "success" && "승인됨"}
                        {status === "error" && "거부됨"}
                    </div>
                </div>

                <Card className="border-white/10 bg-black/40 backdrop-blur-xl overflow-hidden mb-6">
                    <CardContent className="p-0 relative aspect-square bg-black">
                        <QrReader
                            onResult={(result, error) => {
                                if (result) {
                                    handleScan(result.getText())
                                }
                            }}
                            constraints={{ facingMode: 'environment' }}
                            className="w-full h-full object-cover"
                            videoContainerStyle={{ paddingTop: '100%' }}
                            videoStyle={{ objectFit: 'cover' }}
                        />

                        {/* Overlay UI */}
                        <div className="absolute inset-0 border-2 border-white/20 pointer-events-none">
                            <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-48 h-48 border-2 border-primary rounded-lg opacity-50"></div>
                            <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-48 h-1 bg-primary/50 animate-pulse"></div>
                        </div>

                        {/* Status Overlay */}
                        {status !== "idle" && status !== "processing" && (
                            <div className="absolute inset-0 bg-black/80 flex flex-col items-center justify-center z-10 animate-in fade-in duration-200">
                                {status === "success" ? (
                                    <CheckCircle2 className="w-20 h-20 text-green-500 mb-4" />
                                ) : (
                                    <XCircle className="w-20 h-20 text-red-500 mb-4" />
                                )}
                                <h3 className={cn(
                                    "text-2xl font-bold",
                                    status === "success" ? "text-green-500" : "text-red-500"
                                )}>{message}</h3>
                            </div>
                        )}
                    </CardContent>
                    <div className="p-4 text-center border-t border-white/10">
                        <p className="text-sm text-muted-foreground">{message}</p>
                    </div>
                </Card>

                <Card className="border-white/10 bg-black/20">
                    <CardHeader className="pb-2">
                        <CardTitle className="text-sm font-medium flex items-center gap-2">
                            <History className="w-4 h-4" /> 최근 스캔 기록
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        <div className="space-y-2">
                            {logs.length === 0 ? (
                                <p className="text-xs text-muted-foreground text-center py-4">기록이 없습니다.</p>
                            ) : (
                                logs.map((log, i) => (
                                    <div key={i} className="flex items-center justify-between text-sm p-2 rounded bg-white/5">
                                        <span className="text-muted-foreground text-xs">{log.time}</span>
                                        <span className={cn(
                                            "font-medium",
                                            log.status === "success" ? "text-green-400" : "text-red-400"
                                        )}>{log.message}</span>
                                    </div>
                                ))
                            )}
                        </div>
                    </CardContent>
                </Card>
            </div>
            <Footer />
        </main>
    )
}
