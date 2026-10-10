import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from "node:crypto";

export const SESSION_COOKIE = "tradecore_session";
export const SESSION_MAX_AGE = 12 * 60 * 60;
const backendBase = (process.env.TRADECORE_BACKEND_URL ?? "http://localhost:8080").replace(/\/$/, "");

let cachedEncryptionKey: Buffer | undefined;
function encryptionKey() {
  if (cachedEncryptionKey) return cachedEncryptionKey;
  const secret = process.env.SESSION_SECRET;
  if (!secret) throw new Error("SESSION_SECRET is required to create or read login sessions.");
  cachedEncryptionKey = scryptSync(secret, "tradecore-session-v1", 32);
  return cachedEncryptionKey;
}

export function encryptCredential(credential: string) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", encryptionKey(), iv);
  const encrypted = Buffer.concat([cipher.update(credential, "utf8"), cipher.final()]);
  return [iv, cipher.getAuthTag(), encrypted].map((part) => part.toString("base64url")).join(".");
}

export function decryptCredential(value: string) {
  const [ivText, tagText, encryptedText] = value.split(".");
  if (!ivText || !tagText || !encryptedText) throw new Error("Invalid session cookie.");
  const decipher = createDecipheriv("aes-256-gcm", encryptionKey(), Buffer.from(ivText, "base64url"));
  decipher.setAuthTag(Buffer.from(tagText, "base64url"));
  return Buffer.concat([decipher.update(Buffer.from(encryptedText, "base64url")), decipher.final()]).toString("utf8");
}

export function sessionCookieOptions() {
  return { httpOnly: true, sameSite: "strict" as const, secure: process.env.NODE_ENV === "production", path: "/", maxAge: SESSION_MAX_AGE };
}

export function backendAccountUrl() {
  return `${backendBase}/api/v1/account/me`;
}

export function checkSameOrigin(request: Request & { nextUrl: URL }) {
  return request.headers.get("origin") === request.nextUrl.origin;
}
