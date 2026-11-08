"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { api } from "@/services/apiClient";
import {
  AUTH_ENABLED,
  MAX_SEATS_GUEST,
  MAX_SEATS_MEMBER,
} from "@/services/apiConfig";
import type { LoginRequest, SignupRequest, User } from "@/types/api";

interface AuthState {
  /* null is "guest", which is a normal state and not a failure. */
  user: User | null;
  /* False until the startup check settles. The header and the route guards
     both have to wait on this: showing "Sign in" for one frame to someone who
     is already signed in is worse than showing nothing. */
  ready: boolean;
  /* The server caps a hold at 3 for a guest and 6 for a member. Mirrored here
     so the UI can stop you at the limit rather than letting the API refuse
     you, the same reason adjacency is mirrored. The server stays the
     authority; this is only ever a courtesy. */
  maxSeats: number;
  /* These deliberately let the error through. A form needs to render "that
     email is taken" itself, and swallowing it here would leave the caller
     with no way to tell success from failure. */
  logIn: (body: LoginRequest) => Promise<void>;
  signUp: (body: SignupRequest) => Promise<void>;
  logOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  /* Mock mode has no session to find, so there is nothing to wait for. */
  const [ready, setReady] = useState(!AUTH_ENABLED);

  useEffect(() => {
    if (!AUTH_ENABLED) return;

    let cancelled = false;

    void (async () => {
      try {
        const me = await api.me();
        if (!cancelled) setUser(me);
      } catch {
        /* Swallowed on purpose. A 401 here means "you are a guest", which is
           the common case and not something to put on screen. Any other
           failure also lands in guest state, which is the safe default: the
           worst consequence is being asked to sign in again. */
        if (!cancelled) setUser(null);
      } finally {
        if (!cancelled) setReady(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const logIn = useCallback(async (body: LoginRequest) => {
    setUser(await api.logIn(body));
  }, []);

  const signUp = useCallback(async (body: SignupRequest) => {
    setUser(await api.signUp(body));
  }, []);

  const logOut = useCallback(async () => {
    try {
      await api.logOut();
    } finally {
      /* Cleared even when the call failed. The cookies may already be gone,
         and leaving the UI signed in after someone pressed Log out is the
         worse of the two wrong answers. */
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      ready,
      maxSeats: user ? MAX_SEATS_MEMBER : MAX_SEATS_GUEST,
      logIn,
      signUp,
      logOut,
    }),
    [user, ready, logIn, signUp, logOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  /* Throws rather than returning a guest default. A component reading auth
     outside the provider is a wiring mistake, and a silent "you are a guest"
     would hide it until someone noticed they could never sign in. */
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
