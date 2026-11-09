import { Suspense } from "react";

import { ResetPasswordScreen } from "@/screens/ResetPasswordScreen";

export default function Page() {
  /* Required, not decorative: ResetPasswordScreen reads ?token with
     useSearchParams, and Next refuses to prerender a page whose client tree
     reads search params outside a boundary. Same reason as /login. */
  return (
    <Suspense fallback={null}>
      <ResetPasswordScreen />
    </Suspense>
  );
}