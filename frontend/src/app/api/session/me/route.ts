import { NextRequest, NextResponse } from "next/server";
import { backendAccountUrl, decryptCredential, SESSION_COOKIE } from "@/lib/session";

export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  const value = request.cookies.get(SESSION_COOKIE)?.value;
  if (!value) return NextResponse.json({ message: "Not signed in." }, { status: 401 });
  const clear = () => {
    const response = NextResponse.json({ message: "Session expired." }, { status: 401 });
    response.cookies.set(SESSION_COOKIE, "", { httpOnly: true, sameSite: "strict", secure: process.env.NODE_ENV === "production", path: "/", maxAge: 0 });
    return response;
  };
  try {
    const session = JSON.parse(decryptCredential(value)) as { credential: string; email: string };
    const backendResponse = await fetch(backendAccountUrl(), { headers: { Authorization: `Basic ${session.credential}`, Accept: "application/json" }, cache: "no-store", redirect: "manual" });
    if (backendResponse.status === 401) return clear();
    if (!backendResponse.ok) return NextResponse.json({ message: "Unable to restore session." }, { status: 502 });
    await backendResponse.arrayBuffer();
    return NextResponse.json({ email: session.email });
  } catch {
    return clear();
  }
}
