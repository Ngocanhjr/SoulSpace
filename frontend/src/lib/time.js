const fullDateFormat = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});
const relativeDateFormat = new Intl.RelativeTimeFormat("vi-VN", { numeric: "always" });
// shortcut: months/years use 30/365 days; use calendar differences if exact boundaries are needed.
const units = [
  [31536000, "year"],
  [2592000, "month"],
  [86400, "day"],
  [3600, "hour"],
  [60, "minute"],
];

function parseDate(value) {
  if (value == null || value === "") return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateTime(value) {
  const date = parseDate(value);
  return date ? fullDateFormat.format(date) : "";
}

export function formatRelativeTime(value, now = Date.now()) {
  const date = parseDate(value);
  if (!date) return "";

  const seconds = (date.getTime() - now) / 1000;
  for (const [duration, unit] of units) {
    if (Math.abs(seconds) >= duration) {
      return relativeDateFormat.format(Math.trunc(seconds / duration), unit);
    }
  }
  return "Vừa xong";
}
