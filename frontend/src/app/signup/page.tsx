import { Suspense } from "react";

import { SignupScreen } from "@/screens/SignupScreen";

export default function Page() {
  return (
    <Suspense fallback={null}>
      <SignupScreen />
    </Suspense>
  );
}