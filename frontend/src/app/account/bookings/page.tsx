import { RequireAuth } from "@/components/RequireAuth";
import { MyBookingsScreen } from "@/screens/MyBookingsScreen";

export default function Page() {
  return (
    <RequireAuth>
      <MyBookingsScreen />
    </RequireAuth>
  );
}
