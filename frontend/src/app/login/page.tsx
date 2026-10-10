import { LoginForm } from "@/components/login-form";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ registered?: string }> }) {
  const params = await searchParams;
  return <div className="login-page"><LoginForm registered={params.registered === "1"} /></div>;
}
