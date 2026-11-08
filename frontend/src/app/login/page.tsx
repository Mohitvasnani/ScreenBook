import { Suspense } from "react";

import { LoginScreen } from "@/screens/LoginScreen";

export default function Page() {
  /* The Suspense boundary is required, not decorative. LoginScreen reads
     ?next= with useSearchParams, and Next refuses to prerender a page whose
     client tree reads search params outside one. The fallback is effectively
     never seen: the boundary resolves as soon as the params are known. */
  return (
    <Suspense fallback={null}>
      <LoginScreen />
    </Suspense>
  );
}