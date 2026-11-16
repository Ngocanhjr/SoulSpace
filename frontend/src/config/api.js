import axios from "axios";

const defaultBaseUrl = import.meta.env.DEV ? "" : "http://localhost:8080";
const rawBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? defaultBaseUrl).trim();

export const API_BASE_URL = rawBaseUrl.replace(/\/+$/, "");

export function buildApiUrl(path = "") {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${API_BASE_URL}${normalizedPath}`;
}

export const api = axios.create({ baseURL: API_BASE_URL, withCredentials: true });

let csrfToken = null;
let csrfRequest = null;

export function resetCsrf() {
  csrfToken = null;
}

export async function loadCsrf() {
  if (csrfToken) return csrfToken;
  if (!csrfRequest) {
    csrfRequest = api.get("/api/v1/auth/csrf").then(({ data }) => {
      csrfToken = data.token;
      return csrfToken;
    }).finally(() => { csrfRequest = null; });
  }
  return csrfRequest;
}

api.interceptors.request.use(async (config) => {
  if (!["get", "head", "options"].includes((config.method || "get").toLowerCase())) {
    config.headers["X-CSRF-TOKEN"] = await loadCsrf();
  }
  return config;
});

api.interceptors.response.use((response) => response, (error) => {
  if (error.response?.data?.code === "CSRF_INVALID") resetCsrf();
  if (error.response?.data?.code === "AUTH_REQUIRED") {
    resetCsrf();
    window.dispatchEvent(new Event("auth-required"));
  }
  return Promise.reject(error);
});

export function apiErrorMessage(error, fallback = "Cannot connect to the server. Please try again.") {
  const data = error.response?.data;
  return Object.values(data?.fieldErrors || {})[0] || data?.message || fallback;
}
