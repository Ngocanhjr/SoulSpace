const rawBaseUrl = (import.meta.env.VITE_API_BASE_URL || "http://localhost:8080").trim();

export const API_BASE_URL = rawBaseUrl.replace(/\/+$/, "");

export function buildApiUrl(path = "") {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${API_BASE_URL}${normalizedPath}`;
}
