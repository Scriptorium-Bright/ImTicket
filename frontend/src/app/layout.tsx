import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ImTicket",
  description: "블록체인 기반 티켓팅 플랫폼",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
