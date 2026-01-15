export function Footer() {
    return (
        <footer className="border-t border-white/10 bg-black/20 backdrop-blur-sm py-8 mt-20">
            <div className="container mx-auto px-4 text-center text-muted-foreground text-sm">
                <p>&copy; {new Date().getFullYear()} ImTicket. All rights reserved.</p>
                <p className="mt-2">블록체인 기반 프리미엄 티켓팅 플랫폼</p>
            </div>
        </footer>
    )
}
