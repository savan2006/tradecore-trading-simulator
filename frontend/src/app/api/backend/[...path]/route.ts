import { NextRequest, NextResponse } from "next/server";
import { decryptCredential, SESSION_COOKIE } from "@/lib/session";

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ path: string[] }> };

async function forward(request: NextRequest, context: RouteContext, method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE") {
  const stateChanging = method !== "GET";
  if (stateChanging && request.headers.get("origin") !== request.nextUrl.origin) {
    return NextResponse.json({ message: "Invalid request origin." }, { status: 403 });
  }
  const forwardedFor = request.headers.get("x-forwarded-for")?.split(",", 2)[0]?.trim()
    || request.headers.get("x-real-ip")?.trim();
  const { path } = await context.params;
  if (path.length === 0 || path.some((segment) => segment === ".." || segment.includes("\\"))) {
    return NextResponse.json({ message: "Invalid API path." }, { status: 400 });
  }
  const isPublicRegistration = method === "POST" && path.join("/") === "api/v1/auth/register";
  let authorization: string | null = null;
  try {
    const cookie = request.cookies.get(SESSION_COOKIE)?.value;
    if (cookie) {
      const session = JSON.parse(decryptCredential(cookie)) as { credential?: string };
      if (session.credential) authorization = `Basic ${session.credential}`;
    }
  } catch {
    authorization = null;
  }
  if (!authorization?.startsWith("Basic ") && !isPublicRegistration) {
    return NextResponse.json({ message: "Basic authentication is required." }, { status: 401 });
  }
  const backendBase = (process.env.TRADECORE_BACKEND_URL ?? "http://localhost:8080").replace(/\/$/, "");
  const target = `${backendBase}/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;

  try {
    const response = await fetch(target, {
      method,
      headers: {
        ...(authorization ? { Authorization: authorization } : {}),
        ...(request.headers.get("idempotency-key") ? { "Idempotency-Key": request.headers.get("idempotency-key")! } : {}),
        ...(forwardedFor ? { "X-Forwarded-For": forwardedFor } : {}),
        Accept: "application/json",
        ...(request.headers.get("content-type") ? { "Content-Type": request.headers.get("content-type")! } : {}),
      },
      ...(method !== "GET" && method !== "DELETE" ? { body: await request.text() } : {}),
      cache: "no-store",
      redirect: "manual",
    });
    const result = new NextResponse(response.status === 204 || response.status === 304 ? null : await response.arrayBuffer(), {
      status: response.status,
      headers: {
        "content-type": response.headers.get("content-type") ?? "application/json",
        "cache-control": "no-store",
        ...(response.headers.get("x-request-id") ? { "X-Request-Id": response.headers.get("x-request-id")! } : {}),
        ...(response.headers.get("retry-after") ? { "Retry-After": response.headers.get("retry-after")! } : {}),
      },
    });
    if (response.status === 401) result.cookies.set(SESSION_COOKIE, "", { httpOnly: true, sameSite: "strict", secure: process.env.NODE_ENV === "production", path: "/", maxAge: 0 });
    return result;
  } catch {
    return NextResponse.json({ message: "TradeCore backend is unavailable." }, { status: 502 });
  }
}

export async function GET(request: NextRequest, context: RouteContext) {
  return forward(request, context, "GET");
}

export async function POST(request: NextRequest, context: RouteContext) {
  return forward(request, context, "POST");
}

export async function PUT(request: NextRequest, context: RouteContext) {
  return forward(request, context, "PUT");
}

export async function PATCH(request: NextRequest, context: RouteContext) {
  return forward(request, context, "PATCH");
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  return forward(request, context, "DELETE");
}
