"use client"

import * as React from "react"
import { Navbar } from "@/components/layout/navbar"
import { Footer } from "@/components/layout/footer"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { TicketCard } from "@/components/ticket/ticket-card"
import { Wallet, Settings, LogOut, User, Users, Plus, Mail, Check, X, Edit2, Share2, Flame, Ticket } from "lucide-react"
import { useUserStore } from "@/store/useUserStore"
import { groupApi, memberApi, gatewayTicketApi } from "@/services/api"
import { cn } from "@/lib/utils"

export default function MyPage() {
    const { walletAddress, nickname, logout, setNickname } = useUserStore()
    const [activeTab, setActiveTab] = React.useState("tickets")
    const [isLoading, setIsLoading] = React.useState(false)

    // Group State
    const [groupInfo, setGroupInfo] = React.useState<any>(null)
    const [inviteAddress, setInviteAddress] = React.useState("")
    const [invites, setInvites] = React.useState<string[]>([])

    // Nickname State
    const [isEditingNickname, setIsEditingNickname] = React.useState(false)
    const [newNickname, setNewNickname] = React.useState("")

    // Mock Tickets with valid images
    const [myTickets, setMyTickets] = React.useState([
        {
            id: "1",
            title: "네온 드림 콘서트",
            date: "2024. 05. 20 • 19:00",
            location: "예술의 전당",
            seat: "VIP-A-12",
            image: "https://images.unsplash.com/photo-1459749411177-287ce112a8bf?q=80&w=2070&auto=format&fit=crop",
            tokenId: "1042",
            isShared: false
        },
        {
            id: "2",
            title: "디지털 아트 전시회",
            date: "2024. 06. 15 • 14:00",
            location: "DDP 아트홀",
            seat: "General-B-05",
            image: "https://images.unsplash.com/photo-1573164713988-8665fc963095?q=80&w=2069&auto=format&fit=crop",
            tokenId: "2091",
            isShared: true
        }
    ])

    // Fetch Data
    React.useEffect(() => {
        if (walletAddress) {
            if (activeTab === "group") {
                fetchGroupInfo()
                fetchInvites()
            }
        }
    }, [walletAddress, activeTab])

    const fetchGroupInfo = async () => {
        try {
            const res = await groupApi.getGroup(walletAddress!)
            setGroupInfo(res.data)
        } catch (error) {
            console.log("No group found or error fetching group")
            setGroupInfo(null)
        }
    }

    const fetchInvites = async () => {
        try {
            const res = await groupApi.getInvites(walletAddress!)
            setInvites(res.data.invites || [])
        } catch (error) {
            console.error("Error fetching invites", error)
        }
    }

    // --- Nickname Handlers ---
    const handleUpdateNickname = async () => {
        if (!newNickname) return
        try {
            await memberApi.changeNickname(newNickname)
            setNickname(newNickname) // Update store
            setIsEditingNickname(false)
            alert("닉네임이 변경되었습니다.")
        } catch (error) {
            console.error(error)
            alert("닉네임 변경 실패")
        }
    }

    // --- Group Handlers ---
    const handleCreateGroup = async () => {
        if (!walletAddress) return
        setIsLoading(true)
        try {
            await groupApi.createGroup(walletAddress)
            await fetchGroupInfo()
            alert("그룹이 생성되었습니다!")
        } catch (error) {
            console.error(error)
            alert("그룹 생성 실패")
        } finally {
            setIsLoading(false)
        }
    }

    const handleInvite = async () => {
        if (!walletAddress || !inviteAddress) return
        setIsLoading(true)
        try {
            await groupApi.inviteUser(walletAddress, inviteAddress)
            alert("초대를 보냈습니다!")
            setInviteAddress("")
        } catch (error) {
            console.error(error)
            alert("초대 실패")
        } finally {
            setIsLoading(false)
        }
    }

    const handleAcceptInvite = async (groupAddr: string) => {
        if (!walletAddress) return
        try {
            await groupApi.acceptInvite(groupAddr, walletAddress)
            alert("그룹에 가입했습니다!")
            fetchInvites()
            fetchGroupInfo()
        } catch (error) {
            console.error(error)
            alert("가입 실패")
        }
    }

    // --- Ticket Handlers ---
    const handleUseTicket = async (tokenId: string) => {
        if (!walletAddress) return
        if (!confirm("티켓을 사용하시겠습니까? 사용 후에는 되돌릴 수 없습니다.")) return
        try {
            await gatewayTicketApi.use(walletAddress, tokenId)
            alert("티켓을 사용했습니다!")
        } catch (error) {
            console.error(error)
            alert("티켓 사용 실패")
        }
    }

    const handleShareTicket = async (tokenId: string) => {
        if (!walletAddress) return
        if (!confirm("그룹 멤버들과 티켓을 공유하시겠습니까?")) return
        try {
            await gatewayTicketApi.share(walletAddress, tokenId)
            alert("티켓이 공유되었습니다!")
        } catch (error) {
            console.error(error)
            alert("티켓 공유 실패")
        }
    }

    const handleBurnTicket = async (tokenId: string) => {
        if (!walletAddress) return
        if (!confirm("정말 티켓을 삭제(소각)하시겠습니까? 이 작업은 되돌릴 수 없습니다.")) return
        try {
            await gatewayTicketApi.burn(walletAddress, tokenId)
            alert("티켓이 소각되었습니다.")
            setMyTickets(myTickets.filter(t => t.tokenId !== tokenId))
        } catch (error) {
            console.error(error)
            alert("티켓 소각 실패")
        }
    }

    return (
        <main className="min-h-screen bg-background">
            <Navbar />

            <div className="container mx-auto px-4 pt-32 pb-20">
                <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">

                    {/* Sidebar: Profile */}
                    <div className="lg:col-span-1 space-y-6">
                        <Card className="border-primary/20 bg-gradient-to-b from-secondary/50 to-background">
                            <CardHeader className="text-center pb-2">
                                <div className="w-24 h-24 bg-gradient-to-br from-indigo-500 to-purple-500 rounded-full mx-auto mb-4 flex items-center justify-center shadow-lg shadow-purple-500/20">
                                    <User className="w-10 h-10 text-white" />
                                </div>

                                {isEditingNickname ? (
                                    <div className="flex items-center gap-2 justify-center">
                                        <input
                                            className="bg-black/20 border border-white/10 rounded px-2 py-1 text-sm w-24 text-center"
                                            value={newNickname}
                                            onChange={(e) => setNewNickname(e.target.value)}
                                            placeholder="새 닉네임"
                                        />
                                        <Button size="sm" variant="ghost" className="h-7 w-7 p-0" onClick={handleUpdateNickname}>
                                            <Check className="w-4 h-4 text-green-500" />
                                        </Button>
                                        <Button size="sm" variant="ghost" className="h-7 w-7 p-0" onClick={() => setIsEditingNickname(false)}>
                                            <X className="w-4 h-4 text-red-500" />
                                        </Button>
                                    </div>
                                ) : (
                                    <CardTitle className="flex items-center justify-center gap-2">
                                        {nickname || "게스트"}
                                        <Button size="sm" variant="ghost" className="h-6 w-6 p-0 opacity-50 hover:opacity-100" onClick={() => {
                                            setNewNickname(nickname || "")
                                            setIsEditingNickname(true)
                                        }}>
                                            <Edit2 className="w-3 h-3" />
                                        </Button>
                                    </CardTitle>
                                )}

                                <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground bg-black/20 py-1 px-3 rounded-full mt-2 mx-auto w-fit">
                                    <Wallet className="w-3 h-3" />
                                    <span className="font-mono">{walletAddress ? `${walletAddress.slice(0, 10)}...` : "연결되지 않음"}</span>
                                </div>
                            </CardHeader>
                            <CardContent className="space-y-4">
                                <div className="bg-secondary/50 p-4 rounded-lg text-center border border-white/5">
                                    <span className="text-xs text-muted-foreground uppercase tracking-wider">보유 자산</span>
                                    <div className="text-2xl font-bold text-primary mt-1">1.45 ETH</div>
                                </div>

                                <div className="space-y-1">
                                    <Button
                                        variant={activeTab === "tickets" ? "secondary" : "ghost"}
                                        className="w-full justify-start gap-2"
                                        onClick={() => setActiveTab("tickets")}
                                    >
                                        <Wallet className="w-4 h-4" /> 내 티켓
                                    </Button>
                                    <Button
                                        variant={activeTab === "group" ? "secondary" : "ghost"}
                                        className="w-full justify-start gap-2"
                                        onClick={() => setActiveTab("group")}
                                    >
                                        <Users className="w-4 h-4" /> 그룹 관리
                                    </Button>
                                    <Button variant="ghost" className="w-full justify-start gap-2">
                                        <Settings className="w-4 h-4" /> 설정
                                    </Button>
                                    <Button variant="ghost" className="w-full justify-start gap-2 text-destructive hover:text-destructive hover:bg-destructive/10" onClick={logout}>
                                        <LogOut className="w-4 h-4" /> 로그아웃
                                    </Button>
                                </div>
                            </CardContent>
                        </Card>
                    </div>

                    {/* Main Content */}
                    <div className="lg:col-span-3 space-y-8">

                        {activeTab === "tickets" && (
                            <div className="space-y-8">
                                <div className="flex items-center justify-between">
                                    <h2 className="text-3xl font-bold">내 티켓</h2>
                                    <div className="flex gap-2">
                                        <Button variant="outline" size="sm" className="bg-primary/10 border-primary/20 text-primary">사용 가능</Button>
                                        <Button variant="ghost" size="sm">지난 티켓</Button>
                                    </div>
                                </div>

                                <div className="space-y-4">
                                    {myTickets.map((ticket) => (
                                        <div key={ticket.id} className="relative group">
                                            <TicketCard {...ticket} />
                                            {/* Ticket Actions Overlay */}
                                            <div className="absolute top-4 right-4 flex gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                                                <Button size="sm" variant="secondary" className="shadow-lg" onClick={() => handleUseTicket(ticket.tokenId)}>
                                                    <Ticket className="w-4 h-4 mr-1" /> 사용
                                                </Button>
                                                <Button size="sm" variant="secondary" className="shadow-lg" onClick={() => handleShareTicket(ticket.tokenId)}>
                                                    <Share2 className="w-4 h-4 mr-1" /> 공유
                                                </Button>
                                                <Button size="sm" variant="destructive" className="shadow-lg" onClick={() => handleBurnTicket(ticket.tokenId)}>
                                                    <Flame className="w-4 h-4 mr-1" /> 소각
                                                </Button>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}

                        {activeTab === "group" && (
                            <div className="space-y-8">
                                <h2 className="text-3xl font-bold">그룹 관리</h2>

                                {/* Invites Section */}
                                {invites.length > 0 && (
                                    <Card className="border-primary/20 bg-primary/5">
                                        <CardHeader>
                                            <CardTitle className="text-lg flex items-center gap-2">
                                                <Mail className="w-5 h-5" /> 도착한 초대
                                            </CardTitle>
                                        </CardHeader>
                                        <CardContent className="space-y-2">
                                            {invites.map((invite, idx) => (
                                                <div key={idx} className="flex items-center justify-between bg-background/50 p-3 rounded-lg border border-white/10">
                                                    <span className="font-mono text-sm">{invite}</span>
                                                    <div className="flex gap-2">
                                                        <Button size="sm" onClick={() => handleAcceptInvite(invite)}>
                                                            <Check className="w-4 h-4 mr-1" /> 수락
                                                        </Button>
                                                        <Button size="sm" variant="outline">
                                                            <X className="w-4 h-4 mr-1" /> 거절
                                                        </Button>
                                                    </div>
                                                </div>
                                            ))}
                                        </CardContent>
                                    </Card>
                                )}

                                {/* Group Info Section */}
                                {groupInfo ? (
                                    <Card className="border-white/5 bg-black/20 backdrop-blur-xl">
                                        <CardHeader>
                                            <CardTitle>내 그룹</CardTitle>
                                            <CardDescription className="font-mono text-xs">{groupInfo.groupAddress}</CardDescription>
                                        </CardHeader>
                                        <CardContent className="space-y-6">
                                            <div>
                                                <h3 className="font-medium mb-3 flex items-center gap-2">
                                                    <Users className="w-4 h-4" /> 멤버 목록
                                                </h3>
                                                <div className="space-y-2">
                                                    {groupInfo.owners && groupInfo.owners.map((owner: string, idx: number) => (
                                                        <div key={idx} className="flex items-center gap-3 bg-white/5 p-3 rounded-lg">
                                                            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-cyan-500 flex items-center justify-center text-xs font-bold">
                                                                {idx + 1}
                                                            </div>
                                                            <span className="font-mono text-sm">{owner}</span>
                                                            {owner.toLowerCase() === walletAddress?.toLowerCase() && (
                                                                <span className="text-xs bg-primary/20 text-primary px-2 py-0.5 rounded-full">나</span>
                                                            )}
                                                        </div>
                                                    ))}
                                                </div>
                                            </div>

                                            <div className="pt-4 border-t border-white/10">
                                                <h3 className="font-medium mb-3">멤버 초대하기</h3>
                                                <div className="flex gap-2">
                                                    <input
                                                        className="flex-1 h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                        placeholder="지갑 주소를 입력하세요 (0x...)"
                                                        value={inviteAddress}
                                                        onChange={(e) => setInviteAddress(e.target.value)}
                                                    />
                                                    <Button onClick={handleInvite} disabled={isLoading}>
                                                        초대
                                                    </Button>
                                                </div>
                                            </div>
                                        </CardContent>
                                    </Card>
                                ) : (
                                    <Card className="border-dashed border-white/10 bg-transparent">
                                        <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                                            <Users className="w-12 h-12 text-muted-foreground mb-4 opacity-50" />
                                            <h3 className="text-lg font-medium mb-2">소속된 그룹이 없습니다</h3>
                                            <p className="text-muted-foreground mb-6 max-w-sm">
                                                그룹을 생성하여 친구들과 함께 티켓을 관리하고 공유해보세요.
                                            </p>
                                            <Button variant="gradient" onClick={handleCreateGroup} disabled={isLoading}>
                                                <Plus className="w-4 h-4 mr-2" /> 새 그룹 만들기
                                            </Button>
                                        </CardContent>
                                    </Card>
                                )}
                            </div>
                        )}

                    </div>
                </div>
            </div>
            <Footer />
        </main>
    )
}
