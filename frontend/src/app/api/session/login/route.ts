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
    const response = await fetch(backendAccountUrl(), { headers: { Authorization: `Basic ${credential}`, Accept: "application/json" }, cache: "no-store", redirect: "manual", signal: AbortSignal.timeout(20_000) });
    if (!response.ok) {
      const status = response.status === 401 ? 401 : response.status === 400 ? 400 : 502;
      return NextResponse.json({ message: status === 401 ? "Email or password is incorrect." : status === 400 ? "Please check your email and password." : "The server could not verify your sign in." }, { status });
    }
    const user = await response.json() as { email?: string; admin?: boolean };
    const result = NextResponse.json({ email: user.email ?? email.trim(), admin: user.admin === true });
    result.cookies.set(SESSION_COOKIE, encryptCredential(JSON.stringify({ credential, email: email.trim() })), sessionCookieOptions());
    return result;
  } catch (error) {
    if (error instanceof Error && error.name === "TimeoutError") return NextResponse.json({ message: "The server took too long to respond. Please try again." }, { status: 504 });
    return NextResponse.json({ message: "The login server is unreachable. Please try again." }, { status: 503 });
  }
}
