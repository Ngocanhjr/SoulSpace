import { api, loadCsrf, resetCsrf } from "./api.js";
import { refreshSession } from "./auth.js";

const GOOGLE_ACTION_KEY = "googleAction";

export async function startGoogle(intent = "LOGIN", options = {}) {
  const { data } = await api.post("/api/v1/auth/google/start", { intent, ...options });
  const target = new URL(data.authorizationUrl);
  if (target.origin !== "https://accounts.google.com") throw new Error("Invalid Google authorization URL");
  // Only the requested UI action survives the redirect; proofs and tokens never go into storage.
  sessionStorage.setItem(GOOGLE_ACTION_KEY, intent === "REAUTH" ? options.action : intent);
  window.location.assign(target.href);
}

export function takeGoogleAction() {
  const action = sessionStorage.getItem(GOOGLE_ACTION_KEY);
  sessionStorage.removeItem(GOOGLE_ACTION_KEY);
  return action;
}

export async function refreshGoogleSession() {
  resetCsrf();
  await loadCsrf();
  return refreshSession();
}
