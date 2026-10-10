import { NextRequest, NextResponse } from "next/server";
import { backendAccountUrl, checkSameOrigin, encryptCredential, sessionCookieOptions, SESSION_COOKIE } from "@/lib/session";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  if (!checkSameOrigin(request)) return NextResponse.json({ message: "Invalid request origin." }, { status: 403 });
  let email: string;
  let password: string;
  try {
    ({ email, password } = await request.json());
  } catch {
    return NextResponse.json({ message: "Email and password are required." }, { status: 400 });
  }
  if (typeof email !== "string" || typeof password !== "string" || !email.trim() || !password) {
    return NextResponse.json({ message: "Email and password are required." }, { status: 400 });
  }
  const credential = Buffer.from(`${email.trim()}:${password}`, "utf8").toString("base64");
  try {
    const response = await fetch(backendAccountUrl(), { headers: { Authorization: `Basic ${credential}`, Accept: "application/json" }, cache: "no-store", redirect: "manual" });
    if (!response.ok) return NextResponse.json({ message: response.status === 401 ? "Email or password is incorrect." : "Unable to verify login." }, { status: response.status === 401 ? 401 : 502 });
    const user = await response.json() as { email?: string };
    const result = NextResponse.json({ email: user.email ?? email.trim() });
    result.cookies.set(SESSION_COOKIE, encryptCredential(JSON.stringify({ credential, email: email.trim() })), sessionCookieOptions());
    return result;
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unable to create login session.";
    return NextResponse.json({ message }, { status: 503 });
  }
}
