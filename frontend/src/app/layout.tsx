import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TradeCore",
  description: "A virtual trading and portfolio learning platform.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
