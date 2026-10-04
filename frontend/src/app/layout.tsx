import type { Metadata } from "next";
import { AuthProvider } from "@/lib/auth-context";
import { AppShell } from "@/components/app-shell";
import "./globals.css";

export const metadata: Metadata = {
  title: "TradeCore",
  description: "A virtual trading and portfolio learning platform.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body><AuthProvider><AppShell>{children}</AppShell></AuthProvider></body>
    </html>
  );
}
