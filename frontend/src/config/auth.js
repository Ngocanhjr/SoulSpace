import { useSyncExternalStore } from "react";
import { api, loadCsrf, resetCsrf } from "./api.js";

let state = { user: null, ready: false, error: null };
let pending = null;
const listeners = new Set();

function update(user, error = null) {
  // Compatibility data for pages being migrated; /auth/me remains the source of authentication.
  ["isLoggedIn", "userId", "username", "userAvatarUrl"].forEach((key) => localStorage.removeItem(key));
  if (user) {
    localStorage.setItem("isLoggedIn", "true");
    localStorage.setItem("userId", JSON.stringify(user.id));
    localStorage.setItem("username", JSON.stringify(user.username));
    localStorage.setItem("userAvatarUrl", JSON.stringify(user.avatarUrl));
  }
  state = { user, ready: true, error };
  listeners.forEach((listener) => listener());
}

window.addEventListener("auth-required", () => update(null));

function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useAuth() {
  return useSyncExternalStore(subscribe, () => state);
}

export function refreshSession() {
  if (!pending) {
    pending = api.get("/api/v1/auth/me")
      .then(({ data }) => { update(data.user); return data.user; })
      .catch((error) => {
        if (error.response?.status === 401) {
          update(null);
          return null;
        }
        update(null, "Cannot verify your session. Check the backend and retry.");
        throw error;
      })
      .finally(() => { pending = null; });
  }
  return pending;
}

export async function signIn(username, password) {
  if (pending) await pending.catch(() => {});
  await api.post("/api/v1/auth/log-in", { username, password });
  resetCsrf();
  await loadCsrf();
  return refreshSession();
}

export async function signOut() {
  await api.post("/api/v1/auth/log-out");
  clearSession();
}

export function clearSession() {
  resetCsrf();
  update(null);
}
