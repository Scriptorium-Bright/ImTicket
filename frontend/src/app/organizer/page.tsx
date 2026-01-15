"use client"

import * as React from "react"
import { Navbar } from "@/components/layout/navbar"
import { Footer } from "@/components/layout/footer"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from "@/components/ui/card"
import { Building2, CalendarPlus, Upload, Armchair, Plus, Trash2, Save, Image as ImageIcon, Check, ArrowRight } from "lucide-react"
import { venueApi, performanceApi, performanceTimeApi, seatPriceApi, seatApi } from "@/services/api"
import { cn } from "@/lib/utils"

// Types for Seat Initialization
interface RowData {
    row: string
    seatCount: number
}

interface SectionData {
    section: string
    rows: RowData[]
}

interface FloorData {
    floor: number
    sections: SectionData[]
}

export default function OrganizerPage() {
    const [activeTab, setActiveTab] = React.useState("venue")
    const [isLoading, setIsLoading] = React.useState(false)

    // Seat Init State
    const [selectedHallId, setSelectedHallId] = React.useState("")
    const [floors, setFloors] = React.useState<FloorData[]>([
        { floor: 1, sections: [{ section: "A", rows: [{ row: "1", seatCount: 10 }] }] }
    ])

    // Event Creation State
    const [creationStep, setCreationStep] = React.useState(1)
    const [createdPerformanceId, setCreatedPerformanceId] = React.useState<string | null>(null)

    // Step 1: Basic Info
    const [eventForm, setEventForm] = React.useState({
        title: "",
        description: "",
        age: 0,
        startDate: "",
        endDate: "",
        venueType: "CONCERT",
    })
    const [eventImage, setEventImage] = React.useState<File | null>(null)
    const [imagePreview, setImagePreview] = React.useState<string | null>(null)

    // Step 2: Times
    const [timeForm, setTimeForm] = React.useState([{ showDate: "", showTime: "", venueHallId: 1 }])

    // Step 3: Prices
    const [priceForm, setPriceForm] = React.useState([{ seatInfo: "VIP", price: 150000 }])

    // Mock Halls (Replace with API call in real impl)
    const [halls, setHalls] = React.useState<{ id: string, name: string }[]>([
        { id: "1", name: "예술의 전당 - 오페라 극장" },
        { id: "2", name: "잠실 주경기장" }
    ])

    // --- Seat Init Handlers ---
    const handleAddFloor = () => {
        setFloors([...floors, { floor: floors.length + 1, sections: [] }])
    }

    const handleAddSection = (floorIndex: number) => {
        const newFloors = [...floors]
        newFloors[floorIndex].sections.push({ section: "", rows: [] })
        setFloors(newFloors)
    }

    const handleAddRow = (floorIndex: number, sectionIndex: number) => {
        const newFloors = [...floors]
        newFloors[floorIndex].sections[sectionIndex].rows.push({ row: "", seatCount: 0 })
        setFloors(newFloors)
    }

    const handleSaveSeats = async () => {
        if (!selectedHallId) {
            alert("공연장을 선택해주세요.")
            return
        }
        setIsLoading(true)
        try {
            await venueApi.initializeSeats(selectedHallId, floors)
            alert("좌석 배치가 저장되었습니다!")
        } catch (error) {
            console.error(error)
            alert("좌석 배치 저장 실패")
        } finally {
            setIsLoading(false)
        }
    }

    // --- Event Creation Handlers ---
    const handleImageChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        if (e.target.files && e.target.files[0]) {
            const file = e.target.files[0]
            setEventImage(file)
            setImagePreview(URL.createObjectURL(file))
        }
    }

    const handleCreateEventStep1 = async () => {
        if (!eventForm.title || !eventForm.startDate || !eventForm.endDate || !eventImage) {
            alert("필수 정보를 모두 입력해주세요.")
            return
        }

        setIsLoading(true)
        try {
            const formData = new FormData()
            const detailsBlob = new Blob([JSON.stringify(eventForm)], { type: "application/json" })
            formData.append("details", detailsBlob)
            formData.append("image", eventImage)

            // Note: The backend returns a Location header or similar, but for now let's assume we can get the ID.
            // If the backend doesn't return the ID in the body, we might need to parse the Location header.
            // Based on controller: return ResponseEntity.created(location).build();
            // So we need to extract ID from Location header.
            const res = await performanceApi.create(formData)

            // Extract ID from Location header
            const location = res.headers['location']
            let newId = "1" // Fallback
            if (location) {
                const parts = location.split('/')
                newId = parts[parts.length - 1]
            }

            setCreatedPerformanceId(newId)
            setCreationStep(2)
            alert("기본 정보가 저장되었습니다. 공연 회차를 등록해주세요.")
        } catch (error) {
            console.error(error)
            alert("공연 생성 실패")
        } finally {
            setIsLoading(false)
        }
    }

    const handleCreateEventStep2 = async () => {
        if (!createdPerformanceId) return
        setIsLoading(true)
        try {
            const res = await performanceTimeApi.create(createdPerformanceId, timeForm)

            // res.data should be List<PerformanceTimeResponse>
            const createdTimes = res.data

            // Initialize seats for each time
            // This corresponds to 'registerSeats' in SeatController
            if (Array.isArray(createdTimes)) {
                await Promise.all(createdTimes.map((time: any) => seatApi.registerSeats(time.id)))
            }

            setCreationStep(3)
            alert("회차 및 좌석 정보가 생성되었습니다. 좌석 가격을 책정해주세요.")
        } catch (error) {
            console.error(error)
            alert("회차 등록 및 좌석 생성 실패")
        } finally {
            setIsLoading(false)
        }
    }

    const handleCreateEventStep3 = async () => {
        if (!createdPerformanceId) return
        setIsLoading(true)
        try {
            await seatPriceApi.create(createdPerformanceId, priceForm)
            alert("모든 설정이 완료되었습니다! 공연이 성공적으로 게시되었습니다.")
            // Reset
            setCreationStep(1)
            setCreatedPerformanceId(null)
            setEventForm({
                title: "",
                description: "",
                age: 0,
                startDate: "",
                endDate: "",
                venueType: "CONCERT",
            })
            setEventImage(null)
            setImagePreview(null)
            setTimeForm([{ showDate: "", showTime: "", venueHallId: 1 }])
            setPriceForm([{ seatInfo: "VIP", price: 150000 }])
        } catch (error) {
            console.error(error)
            alert("가격 설정 실패")
        } finally {
            setIsLoading(false)
        }
    }

    return (
        <main className="min-h-screen bg-background pb-20">
            <Navbar />

            <div className="container mx-auto px-4 pt-32">
                <div className="flex items-center justify-between mb-8">
                    <div>
                        <h1 className="text-3xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-white to-white/60">
                            주최자 센터
                        </h1>
                        <p className="text-muted-foreground mt-2">
                            공연장 등록, 좌석 배치, 공연 생성을 관리하세요.
                        </p>
                    </div>
                </div>

                {/* Custom Tabs */}
                <div className="flex gap-4 mb-8 border-b border-white/10 pb-1 overflow-x-auto">
                    <button
                        onClick={() => setActiveTab("venue")}
                        className={cn(
                            "px-4 py-2 text-sm font-medium transition-colors relative whitespace-nowrap",
                            activeTab === "venue" ? "text-primary" : "text-muted-foreground hover:text-white"
                        )}
                    >
                        <div className="flex items-center gap-2">
                            <Building2 className="w-4 h-4" /> 공연장 등록
                        </div>
                        {activeTab === "venue" && (
                            <div className="absolute bottom-[-5px] left-0 w-full h-0.5 bg-primary shadow-[0_0_10px_rgba(var(--primary),0.5)]" />
                        )}
                    </button>
                    <button
                        onClick={() => setActiveTab("seat")}
                        className={cn(
                            "px-4 py-2 text-sm font-medium transition-colors relative whitespace-nowrap",
                            activeTab === "seat" ? "text-primary" : "text-muted-foreground hover:text-white"
                        )}
                    >
                        <div className="flex items-center gap-2">
                            <Armchair className="w-4 h-4" /> 좌석 배치
                        </div>
                        {activeTab === "seat" && (
                            <div className="absolute bottom-[-5px] left-0 w-full h-0.5 bg-primary shadow-[0_0_10px_rgba(var(--primary),0.5)]" />
                        )}
                    </button>
                    <button
                        onClick={() => setActiveTab("event")}
                        className={cn(
                            "px-4 py-2 text-sm font-medium transition-colors relative whitespace-nowrap",
                            activeTab === "event" ? "text-primary" : "text-muted-foreground hover:text-white"
                        )}
                    >
                        <div className="flex items-center gap-2">
                            <CalendarPlus className="w-4 h-4" /> 공연 생성
                        </div>
                        {activeTab === "event" && (
                            <div className="absolute bottom-[-5px] left-0 w-full h-0.5 bg-primary shadow-[0_0_10px_rgba(var(--primary),0.5)]" />
                        )}
                    </button>
                </div>

                {/* Content */}
                <div className="space-y-6">
                    {activeTab === "venue" && (
                        <Card className="border-white/5 bg-black/20 backdrop-blur-xl">
                            <CardHeader>
                                <CardTitle>새 공연장 등록</CardTitle>
                                <CardDescription>공연장의 기본 정보를 입력해주세요.</CardDescription>
                            </CardHeader>
                            <CardContent>
                                <div className="grid gap-4 py-4">
                                    <div className="grid gap-2">
                                        <label htmlFor="name" className="text-sm font-medium">공연장 이름</label>
                                        <input id="name" className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50" placeholder="예: 예술의 전당" />
                                    </div>
                                    <div className="grid gap-2">
                                        <label htmlFor="address" className="text-sm font-medium">주소</label>
                                        <input id="address" className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50" placeholder="서울시 서초구..." />
                                    </div>
                                    <Button className="w-full mt-4" variant="gradient">등록하기</Button>
                                </div>
                            </CardContent>
                        </Card>
                    )}

                    {activeTab === "seat" && (
                        <div className="space-y-6">
                            <Card className="border-white/5 bg-black/20 backdrop-blur-xl">
                                <CardHeader>
                                    <CardTitle>좌석 배치 설정</CardTitle>
                                    <CardDescription>공연장의 층, 구역, 열 정보를 설정하여 좌석을 초기화합니다.</CardDescription>
                                </CardHeader>
                                <CardContent className="space-y-6">
                                    <div className="grid gap-2">
                                        <label className="text-sm font-medium">공연장 선택</label>
                                        <select
                                            className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                            value={selectedHallId}
                                            onChange={(e) => setSelectedHallId(e.target.value)}
                                        >
                                            <option value="">공연장을 선택하세요</option>
                                            {halls.map(h => <option key={h.id} value={h.id}>{h.name}</option>)}
                                        </select>
                                    </div>

                                    <div className="space-y-4">
                                        {floors.map((floor, fIdx) => (
                                            <div key={fIdx} className="border border-white/10 rounded-lg p-4 bg-white/5">
                                                <div className="flex items-center justify-between mb-4">
                                                    <h3 className="font-bold text-lg text-primary">{floor.floor}층</h3>
                                                    <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => {
                                                        const newFloors = floors.filter((_, i) => i !== fIdx)
                                                        setFloors(newFloors)
                                                    }}>
                                                        <Trash2 className="w-4 h-4" /> 층 삭제
                                                    </Button>
                                                </div>

                                                <div className="space-y-4 pl-4 border-l-2 border-white/10">
                                                    {floor.sections.map((section, sIdx) => (
                                                        <div key={sIdx} className="bg-black/20 p-3 rounded-md">
                                                            <div className="flex items-center gap-2 mb-2">
                                                                <input
                                                                    className="bg-transparent border-b border-white/20 px-2 py-1 text-sm w-20 focus:outline-none focus:border-primary"
                                                                    placeholder="구역명 (A)"
                                                                    value={section.section}
                                                                    onChange={(e) => {
                                                                        const newFloors = [...floors]
                                                                        newFloors[fIdx].sections[sIdx].section = e.target.value
                                                                        setFloors(newFloors)
                                                                    }}
                                                                />
                                                                <Button variant="ghost" size="sm" className="h-6 w-6 p-0 ml-auto text-muted-foreground" onClick={() => {
                                                                    const newFloors = [...floors]
                                                                    newFloors[fIdx].sections = newFloors[fIdx].sections.filter((_, i) => i !== sIdx)
                                                                    setFloors(newFloors)
                                                                }}>
                                                                    <Trash2 className="w-3 h-3" />
                                                                </Button>
                                                            </div>

                                                            <div className="space-y-2">
                                                                {section.rows.map((row, rIdx) => (
                                                                    <div key={rIdx} className="flex items-center gap-2 text-sm">
                                                                        <span className="text-muted-foreground">열:</span>
                                                                        <input
                                                                            className="bg-transparent border border-white/10 rounded px-2 py-1 w-16 text-center"
                                                                            placeholder="1"
                                                                            value={row.row}
                                                                            onChange={(e) => {
                                                                                const newFloors = [...floors]
                                                                                newFloors[fIdx].sections[sIdx].rows[rIdx].row = e.target.value
                                                                                setFloors(newFloors)
                                                                            }}
                                                                        />
                                                                        <span className="text-muted-foreground">좌석 수:</span>
                                                                        <input
                                                                            type="number"
                                                                            className="bg-transparent border border-white/10 rounded px-2 py-1 w-16 text-center"
                                                                            placeholder="10"
                                                                            value={row.seatCount}
                                                                            onChange={(e) => {
                                                                                const newFloors = [...floors]
                                                                                newFloors[fIdx].sections[sIdx].rows[rIdx].seatCount = parseInt(e.target.value) || 0
                                                                                setFloors(newFloors)
                                                                            }}
                                                                        />
                                                                        <Button variant="ghost" size="sm" className="h-6 w-6 p-0 text-muted-foreground hover:text-destructive" onClick={() => {
                                                                            const newFloors = [...floors]
                                                                            newFloors[fIdx].sections[sIdx].rows = newFloors[fIdx].sections[sIdx].rows.filter((_, i) => i !== rIdx)
                                                                            setFloors(newFloors)
                                                                        }}>
                                                                            <Trash2 className="w-3 h-3" />
                                                                        </Button>
                                                                    </div>
                                                                ))}
                                                                <Button variant="outline" size="sm" className="w-full mt-2 text-xs border-dashed border-white/20" onClick={() => handleAddRow(fIdx, sIdx)}>
                                                                    <Plus className="w-3 h-3 mr-1" /> 열 추가
                                                                </Button>
                                                            </div>
                                                        </div>
                                                    ))}
                                                    <Button variant="outline" size="sm" className="w-full border-dashed border-white/20" onClick={() => handleAddSection(fIdx)}>
                                                        <Plus className="w-4 h-4 mr-1" /> 구역 추가
                                                    </Button>
                                                </div>
                                            </div>
                                        ))}
                                        <Button variant="outline" className="w-full py-6 border-dashed border-white/20 hover:bg-white/5" onClick={handleAddFloor}>
                                            <Plus className="w-5 h-5 mr-2" /> 층 추가
                                        </Button>
                                    </div>

                                    <Button className="w-full" variant="gradient" size="lg" onClick={handleSaveSeats} disabled={isLoading}>
                                        {isLoading ? "저장 중..." : "좌석 배치 저장하기"}
                                    </Button>
                                </CardContent>
                            </Card>
                        </div>
                    )}

                    {activeTab === "event" && (
                        <Card className="border-white/5 bg-black/20 backdrop-blur-xl">
                            <CardHeader>
                                <CardTitle>새 공연 생성</CardTitle>
                                <CardDescription>
                                    {creationStep === 1 && "1단계: 기본 정보 입력"}
                                    {creationStep === 2 && "2단계: 공연 회차 등록"}
                                    {creationStep === 3 && "3단계: 좌석 가격 책정"}
                                </CardDescription>
                            </CardHeader>
                            <CardContent>
                                {/* Step 1: Basic Info */}
                                {creationStep === 1 && (
                                    <div className="grid gap-6">
                                        <div className="flex flex-col items-center justify-center py-8 text-muted-foreground border-2 border-dashed border-white/10 rounded-lg relative overflow-hidden group hover:border-primary/50 transition-colors">
                                            {imagePreview ? (
                                                <img src={imagePreview} alt="Preview" className="absolute inset-0 w-full h-full object-cover opacity-50 group-hover:opacity-30 transition-opacity" />
                                            ) : null}
                                            <div className="relative z-10 flex flex-col items-center">
                                                <ImageIcon className="w-12 h-12 mb-4 opacity-50" />
                                                <p>공연 포스터 이미지를 업로드하세요</p>
                                                <input
                                                    type="file"
                                                    accept="image/*"
                                                    className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
                                                    onChange={handleImageChange}
                                                />
                                                <Button variant="outline" className="mt-4 pointer-events-none">파일 선택</Button>
                                            </div>
                                        </div>

                                        <div className="grid gap-4">
                                            <div className="grid gap-2">
                                                <label className="text-sm font-medium">공연 제목</label>
                                                <input
                                                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                    placeholder="공연 제목을 입력하세요"
                                                    value={eventForm.title}
                                                    onChange={(e) => setEventForm({ ...eventForm, title: e.target.value })}
                                                />
                                            </div>

                                            <div className="grid gap-2">
                                                <label className="text-sm font-medium">공연 설명</label>
                                                <textarea
                                                    className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                    placeholder="공연에 대한 설명을 입력하세요"
                                                    value={eventForm.description}
                                                    onChange={(e) => setEventForm({ ...eventForm, description: e.target.value })}
                                                />
                                            </div>

                                            <div className="grid grid-cols-2 gap-4">
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">관람 연령</label>
                                                    <input
                                                        type="number"
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                        placeholder="0"
                                                        value={eventForm.age}
                                                        onChange={(e) => setEventForm({ ...eventForm, age: parseInt(e.target.value) || 0 })}
                                                    />
                                                </div>
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">공연 유형</label>
                                                    <select
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                        value={eventForm.venueType}
                                                        onChange={(e) => setEventForm({ ...eventForm, venueType: e.target.value })}
                                                    >
                                                        <option value="CONCERT">콘서트</option>
                                                        <option value="MUSICAL">뮤지컬</option>
                                                        <option value="SPORT">스포츠</option>
                                                    </select>
                                                </div>
                                            </div>

                                            <div className="grid grid-cols-2 gap-4">
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">시작일</label>
                                                    <input
                                                        type="date"
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                        value={eventForm.startDate}
                                                        onChange={(e) => setEventForm({ ...eventForm, startDate: e.target.value })}
                                                    />
                                                </div>
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">종료일</label>
                                                    <input
                                                        type="date"
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                                        value={eventForm.endDate}
                                                        onChange={(e) => setEventForm({ ...eventForm, endDate: e.target.value })}
                                                    />
                                                </div>
                                            </div>
                                        </div>
                                        <Button className="w-full mt-6" variant="gradient" onClick={handleCreateEventStep1} disabled={isLoading}>
                                            {isLoading ? "처리 중..." : "다음: 회차 등록"}
                                        </Button>
                                    </div>
                                )}

                                {/* Step 2: Times */}
                                {creationStep === 2 && (
                                    <div className="space-y-6">
                                        {timeForm.map((time, idx) => (
                                            <div key={idx} className="grid grid-cols-3 gap-4 items-end border border-white/10 p-4 rounded-lg">
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">공연 날짜</label>
                                                    <input
                                                        type="date"
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                                                        value={time.showDate}
                                                        onChange={(e) => {
                                                            const newTimeForm = [...timeForm]
                                                            newTimeForm[idx].showDate = e.target.value
                                                            setTimeForm(newTimeForm)
                                                        }}
                                                    />
                                                </div>
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">공연 시간</label>
                                                    <input
                                                        type="time"
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                                                        value={time.showTime}
                                                        onChange={(e) => {
                                                            const newTimeForm = [...timeForm]
                                                            newTimeForm[idx].showTime = e.target.value
                                                            setTimeForm(newTimeForm)
                                                        }}
                                                    />
                                                </div>
                                                <Button variant="ghost" className="text-destructive" onClick={() => {
                                                    const newTimeForm = timeForm.filter((_, i) => i !== idx)
                                                    setTimeForm(newTimeForm)
                                                }}>
                                                    <Trash2 className="w-4 h-4" /> 삭제
                                                </Button>
                                            </div>
                                        ))}
                                        <Button variant="outline" className="w-full border-dashed" onClick={() => setTimeForm([...timeForm, { showDate: "", showTime: "", venueHallId: 1 }])}>
                                            <Plus className="w-4 h-4 mr-2" /> 회차 추가
                                        </Button>
                                        <Button className="w-full mt-6" variant="gradient" onClick={handleCreateEventStep2} disabled={isLoading}>
                                            {isLoading ? "처리 중..." : "다음: 가격 책정"}
                                        </Button>
                                    </div>
                                )}

                                {/* Step 3: Prices */}
                                {creationStep === 3 && (
                                    <div className="space-y-6">
                                        {priceForm.map((price, idx) => (
                                            <div key={idx} className="grid grid-cols-3 gap-4 items-end border border-white/10 p-4 rounded-lg">
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">좌석 등급</label>
                                                    <select
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                                                        value={price.seatInfo}
                                                        onChange={(e) => {
                                                            const newPriceForm = [...priceForm]
                                                            newPriceForm[idx].seatInfo = e.target.value
                                                            setPriceForm(newPriceForm)
                                                        }}
                                                    >
                                                        <option value="VIP">VIP</option>
                                                        <option value="R">R</option>
                                                        <option value="S">S</option>
                                                        <option value="A">A</option>
                                                        <option value="B">B</option>
                                                        <option value="C">C</option>
                                                    </select>
                                                </div>
                                                <div className="grid gap-2">
                                                    <label className="text-sm font-medium">가격 (KRW)</label>
                                                    <input
                                                        type="number"
                                                        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                                                        value={price.price}
                                                        onChange={(e) => {
                                                            const newPriceForm = [...priceForm]
                                                            newPriceForm[idx].price = parseInt(e.target.value) || 0
                                                            setPriceForm(newPriceForm)
                                                        }}
                                                    />
                                                </div>
                                                <Button variant="ghost" className="text-destructive" onClick={() => {
                                                    const newPriceForm = priceForm.filter((_, i) => i !== idx)
                                                    setPriceForm(newPriceForm)
                                                }}>
                                                    <Trash2 className="w-4 h-4" /> 삭제
                                                </Button>
                                            </div>
                                        ))}
                                        <Button variant="outline" className="w-full border-dashed" onClick={() => setPriceForm([...priceForm, { seatInfo: "VIP", price: 150000 }])}>
                                            <Plus className="w-4 h-4 mr-2" /> 등급 추가
                                        </Button>
                                        <Button className="w-full mt-6" variant="gradient" onClick={handleCreateEventStep3} disabled={isLoading}>
                                            {isLoading ? "처리 중..." : "공연 생성 완료"}
                                        </Button>
                                    </div>
                                )}
                            </CardContent>
                        </Card>
                    )}
                </div>
            </div>
            <Footer />
        </main>
    )
}
