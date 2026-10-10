import { NextRequest, NextResponse } from "next/server";
import { checkSameOrigin, SESSION_COOKIE } from "@/lib/session";

export async function POST(request: NextRequest) {
  if (!checkSameOrigin(request)) return NextResponse.json({ message: "Invalid request origin." }, { status: 403 });
  const response = NextResponse.json({ ok: true });
  response.cookies.set(SESSION_COOKIE, "", { httpOnly: true, sameSite: "strict", secure: process.env.NODE_ENV === "production", path: "/", maxAge: 0 });
  return response;
}
