"use client"

import * as React from "react"
import { useParams, useRouter } from "next/navigation"
import { Navbar } from "@/components/layout/navbar"
import { Footer } from "@/components/layout/footer"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { ArrowLeft, RefreshCw, ShieldCheck } from "lucide-react"
import { QRCodeSVG } from "qrcode.react"
import { entryApi } from "@/services/api"

export default function TicketEntryPage() {
    const params = useParams()
    const router = useRouter()
    const id = params?.id as string

    const [token, setToken] = React.useState<string | null>(null)
    const [isLoading, setIsLoading] = React.useState(true)
    const [error, setError] = React.useState<string | null>(null)
    const [timeLeft, setTimeLeft] = React.useState(60)

    const fetchToken = React.useCallback(async () => {
        if (!id) return
        setIsLoading(true)
        setError(null)
        try {
            const res = await entryApi.getToken(id)
            setToken(res.data.token)
            setTimeLeft(60)
        } catch (err: any) {
            console.error(err)
            setError(err.response?.data?.error || "토큰을 불러오는데 실패했습니다.")
        } finally {
            setIsLoading(false)
        }
    }, [id])

    React.useEffect(() => {
        fetchToken()
    }, [fetchToken])

    React.useEffect(() => {
        if (!token) return
        const timer = setInterval(() => {
            setTimeLeft((prev) => {
                if (prev <= 1) {
                    fetchToken()
                    return 60
                }
                return prev - 1
            })
        }, 1000)
        return () => clearInterval(timer)
    }, [token, fetchToken])

    return (
        <main className="min-h-screen bg-background pb-20">
            <Navbar />
            <div className="container mx-auto px-4 pt-32 max-w-md">
                <Button variant="ghost" className="mb-4 pl-0 hover:bg-transparent hover:text-primary" onClick={() => router.back()}>
                    <ArrowLeft className="w-4 h-4 mr-2" /> 뒤로가기
                </Button>

                <Card className="border-white/10 bg-black/40 backdrop-blur-xl">
                    <CardHeader className="text-center">
                        <CardTitle className="text-2xl">모바일 티켓 입장</CardTitle>
                        <CardDescription>입장 시 직원에게 QR 코드를 보여주세요.</CardDescription>
                    </CardHeader>
                    <CardContent className="flex flex-col items-center space-y-8">
                        {isLoading && !token ? (
                            <div className="w-64 h-64 flex items-center justify-center border-2 border-dashed border-white/10 rounded-xl">
                                <RefreshCw className="w-8 h-8 animate-spin text-muted-foreground" />
                            </div>
                        ) : error ? (
                            <div className="text-center text-destructive space-y-4">
                                <p>{error}</p>
                                <Button variant="outline" onClick={fetchToken}>다시 시도</Button>
                            </div>
                        ) : (
                            <div className="relative group">
                                <div className="absolute -inset-1 bg-gradient-to-r from-primary to-purple-600 rounded-2xl blur opacity-25 group-hover:opacity-50 transition duration-1000"></div>
                                <div className="relative bg-white p-6 rounded-xl">
                                    {token && (
                                        <QRCodeSVG
                                            value={token}
                                            size={200}
                                            level={"H"}
                                            includeMargin={true}
                                        />
                                    )}
                                </div>
                                <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                                    {/* Optional overlay or logo */}
                                </div>
                            </div>
                        )}

                        <div className="text-center space-y-2">
                            <div className="flex items-center justify-center gap-2 text-primary font-medium">
                                <ShieldCheck className="w-4 h-4" />
                                <span>보안 QR 코드</span>
                            </div>
                            <p className="text-sm text-muted-foreground">
                                남은 시간: <span className="text-white font-mono">{timeLeft}</span>초
                            </p>
                            <p className="text-xs text-muted-foreground/60">
                                캡처된 이미지는 사용할 수 없습니다.
                            </p>
                        </div>

                        <Button variant="outline" className="w-full" onClick={fetchToken}>
                            <RefreshCw className="w-4 h-4 mr-2" /> 지금 새로고침
                        </Button>
                    </CardContent>
                </Card>
            </div>
            <Footer />
        </main>
    )
}
