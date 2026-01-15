"use client"

import * as React from "react"
import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { QrCode, Share2, Users } from "lucide-react"
import Image from "next/image"

interface TicketProps {
    title: string
    date: string
    location: string
    seat: string
    image: string
    tokenId: string
    isShared?: boolean
}

export function TicketCard({ title, date, location, seat, image, tokenId, isShared }: TicketProps) {
    return (
        <Card className="overflow-hidden border-white/10 group hover:border-primary/50 transition-all duration-300">
            <div className="flex flex-col md:flex-row">
                {/* Image Section */}
                <div className="relative w-full md:w-1/3 h-48 md:h-auto">
                    <Image
                        src={image}
                        alt={title}
                        fill
                        className="object-cover"
                    />
                    <div className="absolute inset-0 bg-gradient-to-r from-black/50 to-transparent" />
                    {isShared && (
                        <div className="absolute top-2 left-2 bg-purple-500/80 backdrop-blur-sm text-white text-xs px-2 py-1 rounded-full flex items-center gap-1">
                            <Users className="w-3 h-3" /> 공유됨
                        </div>
                    )}
                </div>

                {/* Content Section */}
                <div className="flex-1 flex flex-col justify-between p-0">
                    <CardHeader className="pb-2">
                        <div className="flex justify-between items-start">
                            <div>
                                <h3 className="font-bold text-lg group-hover:text-primary transition-colors">{title}</h3>
                                <p className="text-sm text-muted-foreground">{date}</p>
                            </div>
                            <div className="text-right">
                                <span className="block text-xs text-muted-foreground">Token ID</span>
                                <span className="font-mono text-sm">#{tokenId}</span>
                            </div>
                        </div>
                    </CardHeader>

                    <CardContent className="pb-4">
                        <div className="grid grid-cols-2 gap-4 text-sm">
                            <div>
                                <span className="block text-muted-foreground text-xs">장소</span>
                                <span>{location}</span>
                            </div>
                            <div>
                                <span className="block text-muted-foreground text-xs">좌석</span>
                                <span className="font-medium text-primary">{seat}</span>
                            </div>
                        </div>
                    </CardContent>

                    <CardFooter className="bg-secondary/20 border-t border-white/5 p-4 flex justify-between items-center">
                        <Button variant="ghost" size="sm" className="gap-2 text-xs">
                            <Share2 className="w-3 h-3" /> 공유하기
                        </Button>
                        <Button variant="outline" size="sm" className="gap-2 text-xs border-primary/30 hover:bg-primary/10">
                            <QrCode className="w-3 h-3" /> QR 보기
                        </Button>
                    </CardFooter>
                </div>

                {/* Stub Edge Effect (CSS trick or SVG) */}
                <div className="hidden md:block w-4 h-full relative">
                    <div className="absolute top-0 bottom-0 left-0 border-l-2 border-dashed border-white/20" />
                    <div className="absolute -top-2 -left-2 w-4 h-4 bg-background rounded-full" />
                    <div className="absolute -bottom-2 -left-2 w-4 h-4 bg-background rounded-full" />
                </div>
            </div>
        </Card>
    )
}
