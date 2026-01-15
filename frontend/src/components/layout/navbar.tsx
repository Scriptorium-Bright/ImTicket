"use client"

import * as React from "react"
import Link from "next/link"
import { Menu, X, Ticket, Wallet, User } from "lucide-react"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { useUserStore } from "@/store/useUserStore"

import { SignUpModal } from "@/components/auth/SignUpModal"

export function Navbar() {
    const [isOpen, setIsOpen] = React.useState(false)
    const [scrolled, setScrolled] = React.useState(false)
    const { isLoggedIn, walletAddress, connectWallet, logout, isSignUpModalOpen, setSignUpModalOpen, setNickname } = useUserStore()

    React.useEffect(() => {
        const handleScroll = () => {
            setScrolled(window.scrollY > 20)
        }
        window.addEventListener("scroll", handleScroll)
        return () => window.removeEventListener("scroll", handleScroll)
    }, [])

    return (
        <>
            <nav
                className={cn(
                    "fixed top-0 w-full z-50 transition-all duration-300",
                    scrolled ? "glass border-b border-white/10 py-2" : "bg-transparent py-4"
                )}
            >
                <div className="container mx-auto px-4">
                    <div className="flex items-center justify-between">
                        {/* Logo */}
                        <Link href="/" className="flex items-center space-x-2 group">
                            <div className="relative w-8 h-8 bg-gradient-to-tr from-indigo-500 to-purple-500 rounded-lg flex items-center justify-center group-hover:scale-105 transition-transform">
                                <Ticket className="w-5 h-5 text-white" />
                            </div>
                            <span className="text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-white to-white/80">
                                ImTicket
                            </span>
                        </Link>

                        {/* Desktop Menu */}
                        <div className="hidden md:flex items-center space-x-8">
                            <Link href="/" className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors">
                                홈
                            </Link>
                            <Link href="/explore" className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors">
                                공연 둘러보기
                            </Link>
                            {isLoggedIn && (
                                <>
                                    <Link href="/mypage" className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors">
                                        마이페이지
                                    </Link>
                                    <Link href="/organizer" className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors">
                                        주최자 센터
                                    </Link>
                                </>
                            )}

                            {isLoggedIn ? (
                                <div className="flex items-center gap-4">
                                    <div className="text-sm text-muted-foreground font-mono">
                                        {walletAddress?.slice(0, 6)}...{walletAddress?.slice(-4)}
                                    </div>
                                    <Button variant="ghost" size="sm" onClick={logout}>
                                        로그아웃
                                    </Button>
                                </div>
                            ) : (
                                <Button variant="gradient" size="sm" className="gap-2" onClick={connectWallet}>
                                    <Wallet className="w-4 h-4" />
                                    지갑 연결
                                </Button>
                            )}
                        </div>

                        {/* Mobile Menu Button */}
                        <button
                            className="md:hidden text-white"
                            onClick={() => setIsOpen(!isOpen)}
                        >
                            {isOpen ? <X /> : <Menu />}
                        </button>
                    </div>

                    {/* Mobile Menu */}
                    {isOpen && (
                        <div className="md:hidden absolute top-full left-0 w-full glass border-b border-white/10 p-4 flex flex-col space-y-4 animate-accordion-down">
                            <Link
                                href="/"
                                className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors"
                                onClick={() => setIsOpen(false)}
                            >
                                홈
                            </Link>
                            <Link
                                href="/explore"
                                className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors"
                                onClick={() => setIsOpen(false)}
                            >
                                공연 둘러보기
                            </Link>
                            {isLoggedIn && (
                                <>
                                    <Link
                                        href="/mypage"
                                        className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors"
                                        onClick={() => setIsOpen(false)}
                                    >
                                        마이페이지
                                    </Link>
                                    <Link
                                        href="/organizer"
                                        className="text-sm font-medium text-muted-foreground hover:text-primary transition-colors"
                                        onClick={() => setIsOpen(false)}
                                    >
                                        주최자 센터
                                    </Link>
                                </>
                            )}
                            {isLoggedIn ? (
                                <Button variant="ghost" size="sm" className="w-full" onClick={() => { logout(); setIsOpen(false); }}>
                                    로그아웃
                                </Button>
                            ) : (
                                <Button variant="gradient" size="sm" className="w-full gap-2" onClick={() => { connectWallet(); setIsOpen(false); }}>
                                    <Wallet className="w-4 h-4" />
                                    지갑 연결
                                </Button>
                            )}
                        </div>
                    )}
                </div>
            </nav>

            <SignUpModal
                isOpen={isSignUpModalOpen}
                onClose={() => setSignUpModalOpen(false)}
                walletAddress={walletAddress || ""}
                onSuccess={() => {
                    // Refresh or set logged in
                    // Ideally we fetch nickname again
                    if (walletAddress) {
                        // Simple optimistic update or fetch
                        setNickname("New User") // Or fetch from API
                    }
                }}
            />
        </>
    )
}
