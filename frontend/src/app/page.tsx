import { Navbar } from "@/components/layout/navbar"
import { Footer } from "@/components/layout/footer"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Calendar, MapPin, ArrowRight } from "lucide-react"
import Image from "next/image"
import Link from "next/link"

export default function Home() {
  // Mock data for featured events with valid placeholder images
  const featuredEvents = [
    {
      id: 1,
      title: "네온 드림 콘서트",
      date: "2024. 05. 20",
      location: "예술의 전당",
      image: "https://images.unsplash.com/photo-1459749411177-287ce112a8bf?q=80&w=2070&auto=format&fit=crop", // Valid Concert Image
      price: "0.05 ETH"
    },
    {
      id: 2,
      title: "디지털 아트 전시회",
      date: "2024. 06. 15",
      location: "DDP 아트홀",
      image: "https://images.unsplash.com/photo-1573164713988-8665fc963095?q=80&w=2069&auto=format&fit=crop", // Valid Tech/Art Image
      price: "0.02 ETH"
    },
    {
      id: 3,
      title: "사이버펑크 심포니",
      date: "2024. 07. 01",
      location: "롯데 콘서트홀",
      image: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=2070&auto=format&fit=crop", // Valid Music Image
      price: "0.08 ETH"
    }
  ]

  return (
    <main className="min-h-screen bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-indigo-900/20 via-background to-background">
      <Navbar />

      {/* Hero Section */}
      <section className="relative pt-32 pb-20 lg:pt-48 lg:pb-32 overflow-hidden">
        <div className="container mx-auto px-4 relative z-10">
          <div className="text-center max-w-3xl mx-auto space-y-8">
            <h1 className="text-5xl lg:text-7xl font-bold tracking-tight">
              <span className="text-gradient">티켓팅의 미래</span>
              <br />
              ImTicket
            </h1>
            <p className="text-xl text-muted-foreground leading-relaxed">
              차세대 공연 예매를 경험하세요.<br />
              NFT 기술로 투명하고 안전한, 오직 당신만의 티켓을 소유하세요.
            </p>
            <div className="flex items-center justify-center gap-4">
              <Link href="/explore">
                <Button size="lg" variant="gradient" className="rounded-full px-8">
                  공연 둘러보기
                </Button>
              </Link>
              <Button size="lg" variant="outline" className="rounded-full px-8 glass">
                더 알아보기
              </Button>
            </div>
          </div>
        </div>

        {/* Background Elements */}
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[800px] h-[800px] bg-indigo-500/10 rounded-full blur-3xl -z-10" />
        <div className="absolute top-0 right-0 w-[500px] h-[500px] bg-purple-500/10 rounded-full blur-3xl -z-10" />
      </section>

      {/* Featured Events Section */}
      <section className="py-20">
        <div className="container mx-auto px-4">
          <div className="flex items-center justify-between mb-12">
            <h2 className="text-3xl font-bold">주목할 만한 공연</h2>
            <Link href="/explore">
              <Button variant="ghost" className="gap-2">
                전체 보기 <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {featuredEvents.map((event) => (
              <Link href={`/performance/${event.id}`} key={event.id}>
                <Card className="group overflow-hidden border-white/5 hover:border-primary/50 transition-colors h-full">
                  <div className="relative h-48 overflow-hidden">
                    <Image
                      src={event.image}
                      alt={event.title}
                      fill
                      className="object-cover group-hover:scale-110 transition-transform duration-500"
                    />
                    <div className="absolute inset-0 bg-gradient-to-t from-background/80 to-transparent" />
                    <div className="absolute bottom-4 right-4 bg-black/50 backdrop-blur-md px-3 py-1 rounded-full text-xs font-medium border border-white/10">
                      {event.price}
                    </div>
                  </div>
                  <CardHeader>
                    <CardTitle className="text-xl group-hover:text-primary transition-colors">
                      {event.title}
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-2 text-sm text-muted-foreground">
                    <div className="flex items-center gap-2">
                      <Calendar className="w-4 h-4 text-primary" />
                      <span>{event.date}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <MapPin className="w-4 h-4 text-primary" />
                      <span>{event.location}</span>
                    </div>
                  </CardContent>
                  <CardFooter>
                    <Button className="w-full glass-hover" variant="outline">
                      예매하기
                    </Button>
                  </CardFooter>
                </Card>
              </Link>
            ))}
          </div>
        </div>
      </section>

      <Footer />
    </main>
  )
}
