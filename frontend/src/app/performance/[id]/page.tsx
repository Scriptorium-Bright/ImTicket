"use client"

import { Navbar } from "@/components/layout/navbar"
import { Footer } from "@/components/layout/footer"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Calendar, MapPin, Clock, Share2, Heart } from "lucide-react"
import Image from "next/image"
import { useParams } from "next/navigation"
import Link from "next/link"

export default function PerformanceDetail() {
    const params = useParams()
    const id = params.id

    // Mock data - in real app, fetch based on ID
    const event = {
        title: "네온 드림 콘서트",
        description: "빛과 소리의 몰입형 여정을 경험하세요. 네온 드림은 최고의 일렉트로닉 아티스트들과 함께 잊지 못할 시각적, 청각적 자극을 선사합니다. 라이브 공연의 미래를 목격할 기회를 놓치지 마세요.",
        date: "2024. 05. 20",
        time: "19:00",
        location: "예술의 전당",
        price: "0.05 ETH",
        image: "https://images.unsplash.com/photo-1459749411177-287ce112a8bf?q=80&w=2070&auto=format&fit=crop",
        cast: ["DJ 팬텀", "보컬리스트 루나", "비주얼 아티스트 젠"]
    }

    return (
        <main className="min-h-screen bg-background">
            <Navbar />

            {/* Hero Banner */}
            <div className="relative h-[50vh] w-full">
                <Image
                    src={event.image}
                    alt={event.title}
                    fill
                    className="object-cover"
                    priority
                />
                <div className="absolute inset-0 bg-gradient-to-t from-background via-background/50 to-transparent" />
                <div className="absolute bottom-0 left-0 w-full p-8 lg:p-16">
                    <div className="container mx-auto">
                        <h1 className="text-4xl lg:text-6xl font-bold mb-4 text-white drop-shadow-lg">{event.title}</h1>
                        <div className="flex flex-wrap gap-4 text-white/90">
                            <div className="flex items-center gap-2 bg-black/30 backdrop-blur-md px-3 py-1 rounded-full border border-white/10">
                                <Calendar className="w-4 h-4" /> {event.date}
                            </div>
                            <div className="flex items-center gap-2 bg-black/30 backdrop-blur-md px-3 py-1 rounded-full border border-white/10">
                                <MapPin className="w-4 h-4" /> {event.location}
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <div className="container mx-auto px-4 py-12">
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-12">
                    {/* Left Column: Details */}
                    <div className="lg:col-span-2 space-y-8">
                        <section>
                            <h2 className="text-2xl font-bold mb-4">공연 소개</h2>
                            <p className="text-muted-foreground leading-relaxed text-lg">
                                {event.description}
                            </p>
                        </section>

                        <section>
                            <h2 className="text-2xl font-bold mb-4">출연진</h2>
                            <div className="flex gap-4">
                                {event.cast.map((artist, index) => (
                                    <div key={index} className="bg-secondary/50 px-6 py-3 rounded-lg border border-white/5">
                                        <span className="font-medium">{artist}</span>
                                    </div>
                                ))}
                            </div>
                        </section>

                        <section>
                            <h2 className="text-2xl font-bold mb-4">공연장 정보</h2>
                            <Card className="bg-secondary/20 border-white/5">
                                <CardContent className="p-6 flex items-start gap-4">
                                    <MapPin className="w-6 h-6 text-primary mt-1" />
                                    <div>
                                        <h3 className="font-bold text-lg">{event.location}</h3>
                                        <p className="text-muted-foreground">서울 서초구 남부순환로 2406</p>
                                        <div className="mt-4 h-48 bg-gray-800 rounded-lg flex items-center justify-center text-muted-foreground">
                                            지도 준비중
                                        </div>
                                    </div>
                                </CardContent>
                            </Card>
                        </section>
                    </div>

                    {/* Right Column: Booking Card */}
                    <div className="lg:col-span-1">
                        <div className="sticky top-24">
                            <Card className="border-primary/20 shadow-lg shadow-primary/5">
                                <CardContent className="p-6 space-y-6">
                                    <div className="flex justify-between items-center">
                                        <span className="text-muted-foreground">티켓 가격</span>
                                        <span className="text-2xl font-bold text-primary">{event.price}</span>
                                    </div>

                                    <div className="space-y-2">
                                        <label className="text-sm font-medium">날짜 선택</label>
                                        <div className="p-3 rounded-md border bg-background/50 flex items-center justify-between">
                                            <span>{event.date}</span>
                                            <Calendar className="w-4 h-4 text-muted-foreground" />
                                        </div>
                                    </div>

                                    <div className="space-y-2">
                                        <label className="text-sm font-medium">회차 선택</label>
                                        <div className="grid grid-cols-2 gap-2">
                                            <Button variant="outline" className="border-primary text-primary bg-primary/10">
                                                {event.time}
                                            </Button>
                                            <Button variant="outline" disabled>
                                                21:00 (매진)
                                            </Button>
                                        </div>
                                    </div>

                                    <div className="pt-4 space-y-3">
                                        <Link href={`/booking/${id}/seat`} className="w-full">
                                            <Button className="w-full" size="lg" variant="gradient">
                                                좌석 선택하기
                                            </Button>
                                        </Link>
                                        <div className="flex gap-2">
                                            <Button variant="outline" className="flex-1 gap-2">
                                                <Heart className="w-4 h-4" /> 찜하기
                                            </Button>
                                            <Button variant="outline" className="flex-1 gap-2">
                                                <Share2 className="w-4 h-4" /> 공유
                                            </Button>
                                        </div>
                                    </div>

                                    <p className="text-xs text-center text-muted-foreground">
                                        블록체인 기반 티켓팅. 티켓은 NFT로 발행됩니다.
                                    </p>
                                </CardContent>
                            </Card>
                        </div>
                    </div>
                </div>
            </div>

            <Footer />
        </main>
    )
}
