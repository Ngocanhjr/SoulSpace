const defaultBaseUrl = import.meta.env.DEV ? "" : "http://localhost:8080";
const rawBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? defaultBaseUrl).trim();

export const API_BASE_URL = rawBaseUrl.replace(/\/+$/, "");

export function buildApiUrl(path = "") {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${API_BASE_URL}${normalizedPath}`;
}
