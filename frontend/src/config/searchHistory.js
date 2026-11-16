const PREFIX = "soulspace:search-history:";
const MAX_HISTORY = 20;

function storageKey(userId) {
  return PREFIX + (userId || "guest");
}

function cleanHistory(items) {
  if (!Array.isArray(items)) return [];

  const result = [];
  const seen = new Set();

  for (const item of items) {
    if (typeof item !== "string") continue;

    const query = item.trim();
    if (!query || query.length > 2000 || query.includes("\0")) continue;

    const normalized = query
      .normalize("NFC")
      .replace(/\s+/g, " ")
      .toLowerCase();

    if (seen.has(normalized)) continue;

    seen.add(normalized);
    result.push(query);

    if (result.length === MAX_HISTORY) break;
  }

  return result;
}

export function readSearchHistory(userId) {
  try {
    const raw = window.localStorage.getItem(storageKey(userId));
    return cleanHistory(JSON.parse(raw || "[]"));
  } catch {
    return [];
  }
}

export function writeSearchHistory(userId, items) {
  const next = cleanHistory(items);

  try {
    window.localStorage.setItem(storageKey(userId), JSON.stringify(next));
  } catch {
    // Không lưu được lịch sử vẫn cho phép tìm kiếm.
  }

  return next;
}
