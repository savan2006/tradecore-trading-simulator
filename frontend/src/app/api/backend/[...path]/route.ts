import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ path: string[] }> };

async function forward(request: NextRequest, context: RouteContext, method: "GET" | "POST") {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Basic ")) {
    return NextResponse.json({ message: "Basic authentication is required." }, { status: 401 });
  }

  const { path } = await context.params;
  if (path.length === 0 || path.some((segment) => segment === ".." || segment.includes("\\"))) {
    return NextResponse.json({ message: "Invalid API path." }, { status: 400 });
  }
  const backendBase = (process.env.TRADECORE_BACKEND_URL ?? "http://localhost:8080").replace(/\/$/, "");
  const target = `${backendBase}/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;

  try {
    const response = await fetch(target, {
      method,
      headers: {
        Authorization: authorization,
        Accept: "application/json",
        ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      },
      ...(method === "POST" ? { body: await request.text() } : {}),
      cache: "no-store",
      redirect: "manual",
    });
    return new NextResponse(await response.text(), {
      status: response.status,
      headers: {
        "content-type": response.headers.get("content-type") ?? "application/json",
        "cache-control": "no-store",
      },
    });
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
