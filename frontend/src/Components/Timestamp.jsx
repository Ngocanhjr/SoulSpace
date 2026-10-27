import { useEffect, useState } from "react";
import { formatDateTime, formatRelativeTime } from "../lib/time";

export default function Timestamp({ value, relative = true, className, style }) {
  const [now, setNow] = useState(Date.now);
  const fullDate = formatDateTime(value);

  useEffect(() => {
    if (!relative || !fullDate) return;
    const timer = setInterval(() => setNow(Date.now()), 60000);
    return () => clearInterval(timer);
  }, [relative, fullDate]);

  return (
    <time
      className={className}
      style={style}
      dateTime={fullDate ? new Date(value).toISOString() : undefined}
      title={fullDate ? `${fullDate} (giờ Việt Nam)` : undefined}
    >
      {(relative ? formatRelativeTime(value, now) : fullDate) || "Không rõ thời gian"}
    </time>
  );
}
