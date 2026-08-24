"use client"

import * as React from "react"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { authApi, memberApi } from "@/services/api"
import { useUserStore } from "@/store/useUserStore"
import { Loader2, CheckCircle2 } from "lucide-react"

interface SignUpModalProps {
    isOpen: boolean
    onClose: () => void
    walletAddress: string
    onSuccess: () => void
}

const describeSignupError = (error: unknown, phase: string) => {
    let message = "알 수 없는 오류"
    let status: number | undefined
    let url: string | undefined

    if (error instanceof Error && error.message) {
        message = error.message
    }
    if (typeof error === "object" && error !== null && "message" in error) {
        const errorMessage = (error as { message?: unknown }).message
        if (typeof errorMessage === "string" && errorMessage.length > 0) {
            message = errorMessage
        }
    }

    if (typeof error === "object" && error !== null) {
        const axiosError = error as {
            response?: { status?: number }
            config?: { url?: string }
        }
        status = axiosError.response?.status
        url = axiosError.config?.url
    }

    const responseStatus = status ? ` (HTTP ${status}${url ? `, ${url}` : ""})` : ""
    return `${phase}: ${message}${responseStatus}`
}

export function SignUpModal({ isOpen, onClose, walletAddress, onSuccess }: SignUpModalProps) {
    const [step, setStep] = React.useState<1 | 2>(1)
    const [isLoading, setIsLoading] = React.useState(false)
    const loginWithWallet = useUserStore((state) => state.loginWithWallet)
    const [feedback, setFeedback] = React.useState<string | null>(null)

    // Form State
    const [nickname, setNickname] = React.useState("")
    const [phoneNumber, setPhoneNumber] = React.useState("")
    const [verificationCode, setVerificationCode] = React.useState("")

    // SMS State
    const [isSmsSent, setIsSmsSent] = React.useState(false)
    const [isVerified, setIsVerified] = React.useState(false)

    const handleSendSms = async () => {
        if (!phoneNumber) {
            setFeedback("전화번호를 입력해주세요.")
            return
        }
        setIsLoading(true)
        setFeedback(null)
        try {
            // Mock SMS Send
            // await authApi.sendSms(phoneNumber)
            setIsSmsSent(true)
            setFeedback("인증번호가 발송되었습니다. 테스트 코드는 000000입니다.")
        } catch (error) {
            console.error(error)
            setFeedback("SMS 발송에 실패했습니다.")
        } finally {
            setIsLoading(false)
        }
    }

    const handleVerifySms = async () => {
        if (!verificationCode) return
        setIsLoading(true)
        setFeedback(null)
        try {
            // Mock Verification
            if (verificationCode === "000000") {
                setIsVerified(true)
                setFeedback("전화번호 인증이 완료되었습니다.")
            } else {
                // Fallback to real API if needed, or just fail
                const res = await authApi.verifySms(phoneNumber, verificationCode)
                if (res.data.success) {
                    setIsVerified(true)
                    setFeedback("전화번호 인증이 완료되었습니다.")
                } else {
                    setFeedback("인증번호가 일치하지 않습니다.")
                }
            }
        } catch (error) {
            console.error(error)
            setFeedback("인증 확인에 실패했습니다.")
        } finally {
            setIsLoading(false)
        }
    }

    const handleRegister = async () => {
        if (!nickname || !isVerified) {
            setFeedback("모든 정보를 입력하고 인증을 완료해주세요.")
            return
        }
        setIsLoading(true)
        setFeedback(null)
        let phase = "가입 준비"
        try {
            // 1. Sign Message (This should ideally be done in useUserStore or passed down, 
            // but for simplicity we might need to trigger it here or assume it's part of the register flow if the backend requires a signature in the body)
            // The backend RegisterRequest expects: walletAddress, phoneNumber, nickname, signature, message, code

            // We need to sign a message first. 
            // Let's assume we can get the signature via a prop or a service, but since we are inside the modal, 
            // we might need to ask the user to sign now.

            // Ideally, we should use the web3Service to sign.
            // For now, let's assume the parent handles the actual signing or we import web3Service here.
            // Let's import web3Service dynamically or use a prop if possible, but importing is easier.
            const { web3Service } = await import("@/services/web3")
            phase = "REGISTER nonce 발급"
            const nonceRes = await authApi.getNonce(walletAddress, 'REGISTER')
            const message = nonceRes.data.message
            phase = "MetaMask 서명"
            const signature = await web3Service.signMessage(message)

            phase = "회원가입 요청"
            await memberApi.register({
                walletAddress,
                nickname,
                phoneNumber,
                code: verificationCode,
                message,
                signature
            })

            phase = "자동 로그인"
            await loginWithWallet(walletAddress)
            setFeedback("회원가입과 로그인이 완료되었습니다.")
            onSuccess()
            onClose()
        } catch (error) {
            console.error(error)
            setFeedback(`회원가입에 실패했습니다: ${describeSignupError(error, phase)}`)
        } finally {
            setIsLoading(false)
        }
    }

    return (
        <Dialog open={isOpen} onOpenChange={onClose}>
            <DialogContent className="sm:max-w-[425px] bg-black/90 border-white/10 text-white">
                <DialogHeader>
                    <DialogTitle>회원가입</DialogTitle>
                    <DialogDescription>
                        ImTicket 서비스를 이용하기 위해 추가 정보를 입력해주세요.
                    </DialogDescription>
                </DialogHeader>

                {feedback && (
                    <p role="status" className="text-sm text-primary">
                        {feedback}
                    </p>
                )}

                <div className="grid gap-4 py-4">
                    <div className="grid gap-2">
                        <Label htmlFor="wallet">지갑 주소</Label>
                        <Input id="wallet" value={walletAddress} disabled className="bg-white/5 border-white/10" />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="nickname">닉네임</Label>
                        <Input
                            id="nickname"
                            value={nickname}
                            onChange={(e) => setNickname(e.target.value)}
                            placeholder="사용할 닉네임을 입력하세요"
                            className="bg-white/5 border-white/10"
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="phone">전화번호</Label>
                        <div className="flex gap-2">
                            <Input
                                id="phone"
                                value={phoneNumber}
                                onChange={(e) => setPhoneNumber(e.target.value)}
                                placeholder="01012345678"
                                className="bg-white/5 border-white/10"
                                disabled={isVerified}
                            />
                            <Button
                                variant="outline"
                                onClick={handleSendSms}
                                disabled={isLoading || isVerified || isSmsSent}
                                className="whitespace-nowrap"
                            >
                                {isSmsSent ? "재전송" : "인증요청"}
                            </Button>
                        </div>
                    </div>

                    {isSmsSent && !isVerified && (
                        <div className="grid gap-2 animate-in fade-in slide-in-from-top-2">
                            <Label htmlFor="code">인증번호</Label>
                            <div className="flex gap-2">
                                <Input
                                    id="code"
                                    value={verificationCode}
                                    onChange={(e) => setVerificationCode(e.target.value)}
                                    placeholder="인증번호 6자리"
                                    className="bg-white/5 border-white/10"
                                />
                                <Button
                                    variant="outline"
                                    onClick={handleVerifySms}
                                    disabled={isLoading}
                                >
                                    확인
                                </Button>
                            </div>
                        </div>
                    )}

                    {isVerified && (
                        <div className="flex items-center gap-2 text-green-400 text-sm">
                            <CheckCircle2 className="w-4 h-4" />
                            <span>전화번호 인증 완료</span>
                        </div>
                    )}
                </div>

                <DialogFooter>
                    <Button variant="gradient" onClick={handleRegister} disabled={isLoading || !isVerified || !nickname} className="w-full">
                        {isLoading && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                        가입완료
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    )
}
