import type { Metadata } from "next";
import { Archivo, Inter, JetBrains_Mono } from "next/font/google";
import { AuthProvider } from "@/components/AuthProvider";
import { ModeBanner } from "@/components/ModeBanner";
import { MockProvider } from "@/components/MockProvider";
import { SiteHeader } from "@/components/SiteHeader";
import "@/css/globals.css";
import { SiteFooter } from "@/components/SiteFooter";

/* Self-hosted at build time, so no render-blocking request to Google and no
   flash of fallback type. Each .variable is a class defining the CSS variable
   that css/tokens.css reads, which is why all three go on <html>. */
const archivo = Archivo({
  variable: "--font-archivo",
  subsets: ["latin"],
});

const inter = Inter({
  variable: "--font-inter",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

const jetbrains = JetBrains_Mono({
  variable: "--font-jetbrains",
  subsets: ["latin"],
  weight: ["400", "600"],
});

export const metadata: Metadata = {
  title: "ScreenBook",
  description: "Cinema seat booking: two people claim the same seat, exactly one wins.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${archivo.variable} ${inter.variable} ${jetbrains.variable}`}
    >
      <body>
        <MockProvider>
          {/* Inside MockProvider, so nothing that fetches mounts before the
              worker is intercepting. Wraps the header too, which needs to
              know who is signed in. */}
          <AuthProvider>
            {/* Above the sticky header, so it scrolls away. Read once, not
                permanently occupying the viewport on a 375px screen. */}
            <ModeBanner />
            <SiteHeader />
            <main>{children}</main>
            <SiteFooter />
          </AuthProvider>
        </MockProvider>
      </body>
    </html>
  );
}