import { AccountScreen } from "@/screens/AccountScreen";
import { RequireAuth } from "@/components/RequireAuth";

export default function Page() {
  return (
    <RequireAuth>
      <AccountScreen />
    </RequireAuth>
  );
}